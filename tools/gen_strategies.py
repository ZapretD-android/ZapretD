import json
import os

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "strategies.json")
S = [
 ("Split + TLS-record",         "--split 1+s --tlsrec 1+s"),
 ("Disorder + TLS-record",      "--disorder 1+s --tlsrec 1+s"),
 ("Split 2 + Disorder",         "--split 2 --disorder 1+s"),
 ("OOB",                        "--oob 1+s"),
 ("Disorder OOB",               "--disoob 1+s"),
 ("Fake TTL 4",                 "--split 1+s --fake -1 --ttl 4"),
 ("Fake TTL 6",                 "--split 1+s --fake -1 --ttl 6"),
 ("Fake TTL 8",                 "--split 1+s --fake -1 --ttl 8"),
 ("Fake TTL 10",                "--split 1+s --fake -1 --ttl 10"),
 ("Fake + Drop SACK",            "--split 1+s --fake -1 --ttl 8 --drop-sack"),
 ("Fake + SNI google",          "--split 1+s --fake -1 --ttl 8 --fake-sni www.google.com"),
 ("Fake + случайный TLS",       "--split 1+s --fake -1 --ttl 8 --fake-tls-mod rand"),
 ("Многошаговый",               "--disorder 1 --split 1+s --disorder 3+s --split 6+s"),
 ("Disorder + Fake + TLS-rec",  "--disorder 1+s --fake -1 --ttl 8 --tlsrec 1+s"),
 ("Split + TLS-rec + Drop SACK","--tlsrec 1+s --split 1+s --drop-sack"),
]
json.dump([{"name": n, "args": a} for n, a in S], open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
print("Стратегий ByeDPI:", len(S))
