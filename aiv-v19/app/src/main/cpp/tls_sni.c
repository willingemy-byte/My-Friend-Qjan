#include "tls_sni.h"
#include <stdlib.h>
#include <string.h>
struct journal_tls {
    uint32_t base;size_t contiguous;int result,ech;char name[254];
    unsigned char wire[JOURNAL_HELLO_LIMIT],seen[JOURNAL_HELLO_LIMIT/8],hello[JOURNAL_HELLO_LIMIT];
};
static size_t be16(const unsigned char *p){return ((size_t)p[0]<<8)|p[1];}
static size_t be24(const unsigned char *p){return ((size_t)p[0]<<16)|((size_t)p[1]<<8)|p[2];}
journal_tls *journal_tls_new(uint32_t first){journal_tls *s=calloc(1,sizeof(*s));if(s)s->base=first;return s;}
void journal_tls_free(journal_tls *s){if(s){memset(s,0,sizeof(*s));free(s);}}
const char *journal_tls_name(const journal_tls *s){return s?s->name:"";}
int journal_tls_ech(const journal_tls *s){return s?s->ech:0;}
static int parse_hello(journal_tls *s,size_t n){
    const unsigned char *b=s->hello;size_t p=4;
    if(n<4+2+32+1||b[0]!=1)return -2;
    p+=34;size_t session=b[p++];if(session>32||p+session+2>n)return -2;p+=session;
    size_t ciphers=be16(b+p);p+=2;if(ciphers<2||(ciphers&1)||p+ciphers+1>n)return -2;p+=ciphers;
    size_t compression=b[p++];if(!compression||p+compression>n)return -2;p+=compression;
    if(p==n)return 1;
    if(p+2>n)return -2;size_t extensions=be16(b+p);p+=2;if(p+extensions!=n)return -2;
    int sni_seen=0,ech_seen=0;
    while(p<n){
        if(p+4>n)return -2;size_t type=be16(b+p),len=be16(b+p+2);p+=4;if(p+len>n)return -2;
        if(type==0xfe0d){if(ech_seen++)return -2;s->ech=1;} /* ECH or GREASE, not proof of acceptance. */
        if(type==0){
            if(sni_seen++||len<2||be16(b+p)!=len-2)return -2;
            size_t q=p+2,end=p+len;int host_seen=0;
            while(q<end){
                if(q+3>end)return -2;unsigned name_type=b[q++];size_t length=be16(b+q);q+=2;if(!length||q+length>end)return -2;
                if(name_type==0){
                    if(host_seen++||length>253)return -2;size_t label=0;
                    for(size_t i=0;i<length;i++){
                        unsigned c=b[q+i];
                        if(c=='.'){if(!label||label>63||b[q+i-1]=='-')return -2;label=0;}
                        else{if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-')||(!label&&c=='-'))return -2;label++;}
                        s->name[i]=(char)(c>='A'&&c<='Z'?c+32:c);
                    }
                    if(!label||label>63||b[q+length-1]=='-')return -2;s->name[length]=0;
                }q+=length;
            }
        }p+=len;
    }return 1;
}
static int parse_records(journal_tls *s){
    size_t p=0,h=0,n=s->contiguous;
    if(n&&s->wire[0]!=22)return -1;
    while(p+5<=n){
        const unsigned char *b=s->wire+p;if(b[0]!=22||b[1]!=3||b[2]>4)return -2;
        size_t len=be16(b+3);if(!len||len>16384)return -2;
        if(p+5+len>JOURNAL_HELLO_LIMIT||h+len>JOURNAL_HELLO_LIMIT)return -3;
        if(p+5+len>n)return 0;
        memcpy(s->hello+h,b+5,len);h+=len;p+=5+len;
        if(h>=4){if(s->hello[0]!=1)return -1;size_t wanted=4+be24(s->hello+1);if(wanted>JOURNAL_HELLO_LIMIT)return -3;if(h>=wanted)return parse_hello(s,wanted);}
    }return n==JOURNAL_HELLO_LIMIT?-3:0;
}
int journal_tls_feed(journal_tls *s,uint32_t seq,const unsigned char *b,size_t n){
    if(!s)return -3;if(s->result||!n)return s->result;
    int32_t relative=(int32_t)(seq-s->base);
    if(relative<0){size_t skip=(size_t)-(int64_t)relative;if(skip>=n)return 0;b+=skip;n-=skip;relative=0;}
    size_t at=(size_t)relative;if(at>=JOURNAL_HELLO_LIMIT)return s->result=-3;
    size_t keep=n;if(keep>JOURNAL_HELLO_LIMIT-at)keep=JOURNAL_HELLO_LIMIT-at;
    for(size_t i=0;i<keep;i++){size_t k=at+i;unsigned mask=1u<<(k&7);if((s->seen[k/8]&mask)&&s->wire[k]!=b[i])return s->result=-4;s->wire[k]=b[i];s->seen[k/8]|=mask;}
    while(s->contiguous<JOURNAL_HELLO_LIMIT&&(s->seen[s->contiguous/8]&(1u<<(s->contiguous&7))))s->contiguous++;
    s->result=parse_records(s);if(!s->result&&keep<n)s->result=-3;return s->result;
}