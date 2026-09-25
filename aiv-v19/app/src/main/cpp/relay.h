#ifndef JOURNAL_RELAY_H
#define JOURNAL_RELAY_H
#include <stdint.h>
#include <stddef.h>
#include "zdtun.h"
typedef struct {
    void *data;
    int (*should_stop)(void *);
    int (*protect_socket)(void *,int);
    void (*opened)(void *,uint64_t,const zdtun_5tuple_t *);
    void (*updated)(void *,uint64_t,uint64_t,uint64_t,uint64_t,uint64_t,int,int,int,uint64_t);
    void (*direction)(void *,uint64_t,int,uint64_t,uint64_t);
    void (*dns_question)(void *,uint64_t,const char *,int);
    void (*tls_hello)(void *,uint64_t,const char *,int,int);
    void (*problem)(void *,const char *,uint64_t);
} journal_listener;
int journal_relay(int fd,journal_listener *listener);
int journal_dns_question(const unsigned char *data,size_t length,char *name,size_t capacity,int *type);
#endif