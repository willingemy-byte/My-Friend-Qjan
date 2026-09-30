#include "relay.h"
#include "tls_sni.h"
#include <signal.h>
#include <poll.h>
#include <sys/select.h>

typedef struct {uint64_t id,tx,rx,txp,rxp,reported_tx,reported_rx,last_ms;uint32_t tls_base;int tls_started,tls_done,seen_outgoing,seen_incoming;journal_tls *tls;} flow;
typedef struct {
    int fd,fatal;
    uint64_t next_id,bad,unsupported,fragments,forward_errors,previous_tcp,capacity;
    journal_listener *listener;
} context;
static uint64_t millis(clockid_t clock){struct timespec t;clock_gettime(clock,&t);return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;}
static unsigned u16(const unsigned char *p){return (unsigned)p[0]*256+p[1];}
static void put16(unsigned char *p,unsigned value){p[0]=value>>8;p[1]=value;}
static uint32_t u32(const unsigned char *p){return ((uint32_t)u16(p)<<16)|u16(p+2);}
static void put32(unsigned char *p,uint32_t value){put16(p,value>>16);put16(p+2,value);}
static uint16_t checksum(const unsigned char *p,size_t n,uint32_t sum){
    while(n>1){sum+=u16(p);p+=2;n-=2;}if(n)sum+=(uint32_t)*p<<8;
    while(sum>>16)sum=(sum&65535)+(sum>>16);return (uint16_t)~sum;
}
// Validate lengths before passing input to the upstream packet parser.
static int valid_headers(const unsigned char *p,size_t n){
    if(n<20)return 0;
    size_t ih,total;unsigned proto;
    if((p[0]>>4)==4){ih=(p[0]&15)*4;total=u16(p+2);proto=p[9];if(ih<20||total<ih||total>n)return 0;if(u16(p+6)&0x3fff)return 2;}
    else if((p[0]>>4)==6){if(n<40)return 0;ih=40;total=40+u16(p+4);proto=p[6];if(total>n)return 0;}
    else return 0;
    size_t payload=total-ih;
    if(proto==6){if(payload<20)return 0;unsigned th=(p[ih+12]>>4)*4;if(th<20||th>payload)return 0;}
    if(proto==17&&(payload<8||u16(p+ih+4)<8||u16(p+ih+4)!=payload))return 0;
    return 1;
}
int journal_dns_question(const unsigned char *p,size_t n,char *name,size_t cap,int *type){
    if(n<17||cap<2||(p[2]&0xf8)||u16(p+4)==0)return 0;
    size_t at=12,out=0;int labels=0;
    while(at<n){
        unsigned len=p[at++];if(!len){if(!labels||at+4>n)return 0;name[out]=0;*type=(int)u16(p+at);return 1;}
        if(len>63||at+len>n||out+len+(labels?1:0)>=cap||out+len+(labels?1:0)>253)return 0;
        if(labels++)name[out++]='.';
        for(unsigned i=0;i<len;i++){unsigned c=p[at++];if(c<33||c>126||c=='.'||c=='\\')return 0;name[out++]=(char)c;}
    }
    return 0;
}
static int write_packet(context *c,const void *data,size_t size){
    ssize_t n;do{n=write(c->fd,data,size);}while(n<0&&errno==EINTR);
    if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){
        struct pollfd p={c->fd,POLLOUT,0};if(poll(&p,1,250)>0){do{n=write(c->fd,data,size);}while(n<0&&errno==EINTR);}
    }
    if(n!=(ssize_t)size){c->fatal=3;return -1;}return 0;
}
static int send_client(zdtun_t *tun,zdtun_pkt_t *pkt,const zdtun_conn_t *conn){(void)conn;return write_packet(zdtun_userdata(tun),pkt->buf,pkt->len);}
static int opened(zdtun_t *tun,zdtun_conn_t *conn){
    context *c=zdtun_userdata(tun);flow *f=calloc(1,sizeof(*f));if(!f){c->fatal=4;return 1;}
    f->id=++c->next_id;f->last_ms=millis(CLOCK_REALTIME);zdtun_conn_set_userdata(conn,f);
    c->listener->opened(c->listener->data,f->id,zdtun_conn_get_5tuple(conn));return 0;
}
static void report(context *c,const zdtun_conn_t *conn,int closed){
    flow *f=zdtun_conn_get_userdata(conn);if(!f)return;
    if(!closed&&f->tx==f->reported_tx&&f->rx==f->reported_rx)return;
    c->listener->updated(c->listener->data,f->id,f->tx,f->rx,f->txp,f->rxp,zdtun_conn_get_status(conn),zdtun_conn_get_error(conn),closed,f->last_ms);
    f->reported_tx=f->tx;f->reported_rx=f->rx;
}
static void closed(zdtun_t *tun,const zdtun_conn_t *conn){
    context *c=zdtun_userdata(tun);flow *f=zdtun_conn_get_userdata(conn);
    if(f&&!f->tls_done&&f->tls_started&&c->listener->tls_hello)c->listener->tls_hello(c->listener->data,f->id,"",-5,0);
    report(c,conn,1);if(f){journal_tls_free(f->tls);free(f);}zdtun_conn_set_userdata((zdtun_conn_t *)conn,NULL);
}
static int report_one(zdtun_t *tun,const zdtun_conn_t *conn,void *data){(void)tun;report(data,conn,0);return 0;}
static void account(zdtun_t *tun,const zdtun_pkt_t *pkt,uint8_t outgoing,const zdtun_conn_t *conn){
    context *c=zdtun_userdata(tun);flow *f=zdtun_conn_get_userdata(conn);if(!f)return;
    uint64_t now=millis(CLOCK_REALTIME);
    if(outgoing){f->tx+=pkt->len;f->txp++;}else{f->rx+=pkt->len;f->rxp++;}f->last_ms=now;
    if(outgoing&&!f->seen_outgoing){
        f->seen_outgoing=1;if(c->listener->direction)c->listener->direction(c->listener->data,f->id,1,pkt->len,now);
    }else if(!outgoing&&!f->seen_incoming){
        f->seen_incoming=1;if(c->listener->direction)c->listener->direction(c->listener->data,f->id,0,pkt->len,now);
    }
    if(outgoing&&pkt->tuple.ipproto==6&&!f->tls_done){
        const unsigned char *tcp=(const unsigned char *)pkt->l4;int syn=(tcp[13]&2)!=0;
        if(syn&&!f->tls_started){f->tls_started=1;f->tls_base=u32(tcp+4)+1;}
        if(pkt->l7_len>0&&f->tls_started){
            if(!f->tls)f->tls=journal_tls_new(f->tls_base);
            int result=f->tls?journal_tls_feed(f->tls,u32(tcp+4)+(syn?1:0),(const unsigned char *)pkt->l7,pkt->l7_len):-6;
            if(result){if(c->listener->tls_hello)c->listener->tls_hello(c->listener->data,f->id,result==1?journal_tls_name(f->tls):"",result,result==1?journal_tls_ech(f->tls):0);f->tls_done=1;journal_tls_free(f->tls);f->tls=NULL;}
        }
    }
    if(outgoing&&pkt->tuple.ipproto==17&&ntohs(pkt->tuple.dst_port)==53){
        char name[256];int type;
        if(journal_dns_question((const unsigned char *)pkt->l7,pkt->l7_len,name,sizeof(name),&type))c->listener->dns_question(c->listener->data,f->id,name,type);
    }
}
static void socket_open(zdtun_t *tun,int fd){context *c=zdtun_userdata(tun);if(!c->listener->protect_socket(c->listener->data,fd))c->fatal=2;}
static void problems(context *c){
    uint64_t *values[]={&c->bad,&c->unsupported,&c->fragments,&c->forward_errors,&c->previous_tcp,&c->capacity};
    const char *labels[]={"Paquets non décodés","Protocoles ou extensions non relayés","Fragments IP non relayés","Erreurs de relais","Connexions TCP antérieures réinitialisées pour reconnexion","Limite de sockets atteinte; anciennes connexions fermées"};
    for(unsigned i=0;i<6;i++)if(*values[i]){c->listener->problem(c->listener->data,labels[i],*values[i]);*values[i]=0;}
}
// A stream opened before VPN activation cannot be relayed halfway through TLS.
// Send a valid reset so its app can establish a fresh connection.
static void reset_previous(context *c,const zdtun_pkt_t *pkt){
    const unsigned char *tcp=(const unsigned char *)pkt->l4;
    if(tcp[13]&4)return;
    unsigned char out[60]={0};size_t ih=pkt->tuple.ipver==4?20:40;unsigned char *reply=out+ih;
    if(ih==20){out[0]=0x45;put16(out+2,40);out[8]=64;out[9]=6;memcpy(out+12,pkt->buf+16,4);memcpy(out+16,pkt->buf+12,4);put16(out+10,checksum(out,20,0));}
    else{out[0]=0x60;put16(out+4,20);out[6]=6;out[7]=64;memcpy(out+8,pkt->buf+24,16);memcpy(out+24,pkt->buf+8,16);}
    memcpy(reply,tcp+2,2);memcpy(reply+2,tcp,2);reply[12]=0x50;
    if(tcp[13]&16){put32(reply+4,u32(tcp+8));reply[13]=4;}
    else{put32(reply+8,u32(tcp+4)+pkt->l7_len+((tcp[13]&2)?1:0)+((tcp[13]&1)?1:0));reply[13]=20;}
    uint32_t sum=26;const unsigned char *addresses=out+(ih==20?12:8);size_t address_len=ih==20?8:32;
    for(size_t i=0;i<address_len;i+=2)sum+=u16(addresses+i);
    put16(reply+16,checksum(reply,20,sum));
    if(write_packet(c,out,ih+20)==0){
        uint64_t id=++c->next_id;c->listener->opened(c->listener->data,id,&pkt->tuple);
        c->listener->updated(c->listener->data,id,pkt->len,ih+20,1,1,CONN_STATUS_RESET,0,1,millis(CLOCK_REALTIME));c->previous_tcp++;
    }
}
int journal_relay(int fd,journal_listener *listener){
    if(fd<0||fd>=FD_SETSIZE)return -1;
    context c={.fd=fd,.listener=listener};signal(SIGPIPE,SIG_IGN);
    zdtun_callbacks_t callbacks={.send_client=send_client,.account_packet=account,.on_socket_open=socket_open,.on_connection_open=opened,.on_connection_close=closed};
    zdtun_t *tun=zdtun_init(&callbacks,&c);if(!tun)return -4;zdtun_set_mtu(tun,1500);
    unsigned char buffer[65535];uint64_t report_at=millis(CLOCK_MONOTONIC)+5000,purge_at=millis(CLOCK_MONOTONIC)+1000;
    while(!c.fatal&&!listener->should_stop(listener->data)){
        fd_set rd,wr;FD_ZERO(&rd);FD_ZERO(&wr);int maxfd=0;zdtun_fds(tun,&maxfd,&rd,&wr);FD_SET(fd,&rd);if(fd>maxfd)maxfd=fd;
        struct timeval wait={0,200000};int ready=select(maxfd+1,&rd,&wr,NULL,&wait);
        if(ready<0){if(errno==EINTR)continue;c.fatal=5;break;}
        if(FD_ISSET(fd,&rd))for(int batch=0;batch<32&&!c.fatal&&!listener->should_stop(listener->data);batch++){
            ssize_t n=read(fd,buffer,sizeof(buffer));
            if(n<0){if(errno==EINTR){batch--;continue;}if(errno!=EAGAIN&&errno!=EWOULDBLOCK)c.fatal=6;break;}
            if(!n){c.fatal=6;break;}
            int valid=valid_headers(buffer,(size_t)n);if(!valid){c.bad++;continue;}if(valid==2){c.fragments++;continue;}
            zdtun_pkt_t pkt;int parsed=zdtun_parse_pkt(tun,(const char *)buffer,(uint16_t)n,&pkt);
            if(parsed){c.unsupported++;continue;}
            if(pkt.flags&ZDTUN_PKT_IS_FRAGMENT){c.fragments++;continue;}
            if(pkt.tuple.ipproto!=6&&pkt.tuple.ipproto!=17&&pkt.tuple.ipproto!=1){c.unsupported++;continue;}
            zdtun_conn_t *conn=zdtun_lookup(tun,&pkt.tuple,0);
            if(!conn&&pkt.tuple.ipproto==6&&(((unsigned char *)pkt.l4)[13]&0x12)!=2){reset_previous(&c,&pkt);continue;}
            if(!conn){zdtun_statistics_t stats;zdtun_get_stats(tun,&stats);if(stats.num_open_sockets>=512)c.capacity++;conn=zdtun_lookup(tun,&pkt.tuple,1);}
            if(!conn){c.forward_errors++;continue;}
            if(zdtun_forward(tun,&pkt,conn)!=0){c.forward_errors++;zdtun_conn_close(tun,conn,CONN_STATUS_ERROR);if(zdtun_conn_get_userdata(conn))closed(tun,conn);}
        }
        // Re-poll after outgoing packets: a closed fd might have been reused.
        FD_ZERO(&rd);FD_ZERO(&wr);maxfd=0;zdtun_fds(tun,&maxfd,&rd,&wr);struct timeval zero={0,0};
        ready=select(maxfd+1,&rd,&wr,NULL,&zero);
        if(ready>0&&zdtun_handle_fd(tun,&rd,&wr)!=0)c.forward_errors++;
        uint64_t now=millis(CLOCK_MONOTONIC);
        if(now>=report_at){zdtun_iter_connections(tun,report_one,&c);problems(&c);report_at=now+5000;}
        if(now>=purge_at){zdtun_purge_expired(tun);purge_at=now+1000;}
    }
    zdtun_finalize(tun);problems(&c);return c.fatal?-c.fatal:0;
}