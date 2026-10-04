#!/usr/bin/env python3
"""Real native relay on a local datagram TUN substitute + loopback UDP echo.
No Android VPN, cellular network or Java/JNI attribution is exercised.
"""
from pathlib import Path
import subprocess,tempfile,os
ROOT=Path(__file__).resolve().parents[1]
host=b'ads.example'
names=b'\0'+len(host).to_bytes(2,'big')+host
sni=len(names).to_bytes(2,'big')+names
extensions=b'\0\0'+len(sni).to_bytes(2,'big')+sni
body=b'\x03\x03'+bytes(32)+b'\0\0\2\x13\1\1\0'+len(extensions).to_bytes(2,'big')+extensions
handshake=b'\1'+len(body).to_bytes(3,'big')+body
hello=b'\x16\x03\x03'+len(handshake).to_bytes(2,'big')+handshake
test=r'''
#include "relay.h"
#include "tls_sni.h"
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <poll.h>
#include <fcntl.h>
static const unsigned char hello[]={__HELLO__};
static atomic_int stop_requested;
static uint64_t tx,rx,tp,rp,first_out,first_in,problems;
static int opens,closes,result,worker_fd;
static int stop(void *d){(void)d;return atomic_load(&stop_requested);}
static int protect_socket(void *d,int fd){(void)d;(void)fd;return 1;}
static void opened(void*d,uint64_t id,const zdtun_5tuple_t*t){(void)d;assert(id==1&&t->ipproto==17);opens++;}
static void update(void*d,uint64_t id,uint64_t a,uint64_t b,uint64_t c,uint64_t e,int s,int err,int closed,uint64_t ms){(void)d;(void)s;(void)err;(void)ms;assert(id==1);tx=a;rx=b;tp=c;rp=e;closes+=closed;}
static void direction(void*d,uint64_t id,int outgoing,uint64_t bytes,uint64_t ms){(void)d;(void)ms;assert(id==1);if(outgoing)first_out=bytes;else first_in=bytes;}
static void dns(void*d,uint64_t id,const char*n,int type){(void)d;(void)id;(void)n;(void)type;}
static void tls(void*d,uint64_t id,const char*n,int status,int ech){(void)d;(void)id;(void)n;(void)status;(void)ech;}
static void problem(void*d,const char*n,uint64_t count){(void)d;(void)n;problems+=count;}
static journal_listener listener={.should_stop=stop,.protect_socket=protect_socket,.opened=opened,.updated=update,.direction=direction,.dns_question=dns,.tls_hello=tls,.problem=problem};
static void* run(void*d){(void)d;result=journal_relay(worker_fd,&listener);return NULL;}
static void ready(int fd){struct pollfd p={fd,POLLIN,0};assert(poll(&p,1,2000)>0);}
static void put16(unsigned char*p,unsigned n){p[0]=n>>8;p[1]=n;}
int main(void){
 char name[256];int type;unsigned char question[]={0,1,1,0,0,1,0,0,0,0,0,0,3,'a','d','s',7,'e','x','a','m','p','l','e',0,0,1,0,1};
 assert(journal_dns_question(question,sizeof(question),name,sizeof(name),&type)==1&&!strcmp(name,"ads.example")&&type==1);
 for(size_t n=0;n<sizeof(question);n++)assert(journal_dns_question(question,n,name,sizeof(name),&type)==0);
 question[12]=0xc0;assert(journal_dns_question(question,sizeof(question),name,sizeof(name),&type)==0);
 puts("PASS native DNS question, truncated labels/fields and unsupported compression");
 journal_tls *t=journal_tls_new(1000);assert(t);size_t half=sizeof(hello)/2;
 assert(journal_tls_feed(t,1000+half,hello+half,sizeof(hello)-half)==0);
 assert(journal_tls_feed(t,1000,hello,half)==1&&!strcmp(journal_tls_name(t),"ads.example"));journal_tls_free(t);
 t=journal_tls_new(1000);assert(journal_tls_feed(t,1000,hello,16)==0);unsigned char corrupt[8];memcpy(corrupt,hello+8,8);corrupt[0]^=1;
 assert(journal_tls_feed(t,1008,corrupt,8)==-4);journal_tls_free(t);
 t=journal_tls_new(1000);assert(journal_tls_feed(t,1000+JOURNAL_HELLO_LIMIT,hello,1)==-3);journal_tls_free(t);
 puts("PASS native TLS SNI, split/out-of-order ClientHello, conflicting retransmission and 32 KiB bound");

 int server=socket(AF_INET,SOCK_DGRAM,0),tun[2];assert(server>=0&&socketpair(AF_UNIX,SOCK_DGRAM,0,tun)==0);
 struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};assert(bind(server,(struct sockaddr*)&address,sizeof(address))==0);socklen_t size=sizeof(address);assert(getsockname(server,(struct sockaddr*)&address,&size)==0);
 worker_fd=tun[0];assert(fcntl(worker_fd,F_SETFL,O_NONBLOCK)==0);pthread_t thread;assert(pthread_create(&thread,NULL,run,NULL)==0);
 for(int payload=4;payload<=7;payload+=3){
  unsigned char packet[64]={0};int length=28+payload;packet[0]=0x45;put16(packet+2,length);packet[8]=64;packet[9]=17;
  packet[12]=10;packet[13]=203;packet[15]=1;packet[16]=127;packet[19]=1;put16(packet+20,45000);memcpy(packet+22,&address.sin_port,2);put16(packet+24,8+payload);memcpy(packet+28,"fixture",payload);
  assert(write(tun[1],packet,length)==length);ready(server);unsigned char received[64];struct sockaddr_in peer;socklen_t peer_size=sizeof(peer);int n=recvfrom(server,received,sizeof(received),0,(struct sockaddr*)&peer,&peer_size);
  assert(n==payload&&!memcmp(received,packet+28,payload));assert(sendto(server,received,n,0,(struct sockaddr*)&peer,peer_size)==n);
  ready(tun[1]);int returned=read(tun[1],received,sizeof(received));assert(returned==length&&!memcmp(received+28,packet+28,payload));
 }
 atomic_store(&stop_requested,1);assert(pthread_join(thread,NULL)==0);
 assert(result==0&&opens==1&&closes==1&&tx==67&&rx==67&&tp==2&&rp==2&&first_out==32&&first_in==32&&problems==0);
 close(server);close(tun[0]);close(tun[1]);
 puts("PASS actual native relay: one connection, 2 TX / 2 RX packets, 67 TX / 67 RX IP bytes, close flush and directions");
}
'''.replace('__HELLO__',','.join(str(x) for x in hello))
with tempfile.TemporaryDirectory() as directory:
    tmp=Path(directory);src=tmp/'native-test.c';src.write_text(test);binary=tmp/'native-test'
    cpp=ROOT/'app/src/main/cpp';vendor=ROOT/'third_party/zdtun'
    subprocess.run(['cc','-std=gnu11','-g','-O1','-DNO_DEBUG','-D_LITTLE_ENDIAN','-fsanitize=address,undefined','-fno-omit-frame-pointer','-pthread','-I'+str(cpp),'-I'+str(vendor),str(src),str(cpp/'relay.c'),str(cpp/'tls_sni.c'),str(vendor/'zdtun.c'),str(vendor/'utils.c'),'-o',str(binary)],check=True,timeout=30)
    # This managed container denies LeakSanitizer's /proc task enumeration.
    # AddressSanitizer and UndefinedBehaviorSanitizer remain enabled.
    env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0')
    subprocess.run([str(binary)],check=True,timeout=20,env=env)
