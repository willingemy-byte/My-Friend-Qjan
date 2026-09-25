#ifndef JOURNAL_TLS_SNI_H
#define JOURNAL_TLS_SNI_H
#include <stdint.h>
#include <stddef.h>
/* Passive, bounded observation of the first TLS ClientHello over TCP. */
#define JOURNAL_HELLO_LIMIT 32768
typedef struct journal_tls journal_tls;
journal_tls *journal_tls_new(uint32_t first_sequence);
/* 0 waiting; 1 parsed; -1 non TLS; -2 malformed; -3 limit; -4 conflicting retransmission. */
int journal_tls_feed(journal_tls *,uint32_t sequence,const unsigned char *,size_t);
const char *journal_tls_name(const journal_tls *);
int journal_tls_ech(const journal_tls *);
void journal_tls_free(journal_tls *);
#endif