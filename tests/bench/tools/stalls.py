import re,collections,sys
def analyse(L):
    lines=open(L,errors='replace').read().split('\n')
    hb=[i for i,l in enumerate(lines) if 'labor heartbeat' in l]
    blk=lines[hb[-1]:]
    P=re.compile(r'stall body=(\w+) for=(\d+)t head=(\w+) (\w+) at \S+@(-?\d+), (-?\d+), (-?\d+) queued=(\d+) why=(\S+) kind=(\S+) \[(\w+)@[^\]]*goods=(\S+)[^\]]*\](?: after=(\w+) (\w+) kind=(\S+) held=(\S+?)(?::(\S+?):(\w+)(?:\((\d+)t\))?)? \[(\w+)@(-?\d+), (-?\d+), (-?\d+) goods=(\S+))?')
    S={}
    for l in blk:
        m=P.search(l)
        if m: S[m.group(1)]=m.groups()
    print(L.split('/')[-2],'stalled bodies in last dump:',len(S))
    # chain following
    ends=collections.Counter(); cyc=0; depth=[]; cycles=[]
    for b in S:
        seen=[b]; cur=b
        while True:
            g=S[cur]; holder=g[15]; where=g[16]; hstand=g[17]
            if g[8]!='after': ends['head '+g[8]+' '+g[9].split('/')[-1]]+=1; break
            if holder in (None,'-','none'): ends['pred unheld '+str(g[14]).split('/')[-1]+' '+str(g[13])]+=1; break
            if holder not in S: ends[f'{hstand} holder: {g[14].split("/")[-1]} {g[13]} at {("hand" if where=="hand" else "queue")}']+=1; break
            if holder in seen:
                cyc+=1; cycles.append(seen[seen.index(holder):]); ends['CYCLE']+=1; break
            seen.append(holder); cur=holder
        depth.append(len(seen))
    print(' chain ends:',ends.most_common(12))
    print(' chain depth: median',sorted(depth)[len(depth)//2],'max',max(depth),' bodies in cycles',cyc,' distinct cycles',len({frozenset(c) for c in cycles}), 'cycle sizes',collections.Counter(len(c) for c in {tuple(sorted(c)) for c in cycles}).most_common(5))
    # what a stalled body's own head is, and for how long
    print(' own head:',collections.Counter((g[9].split('/')[-1],g[10]) for g in S.values()).most_common(6))
    dur=sorted(int(g[1]) for g in S.values()); print(' stalled for: median',dur[len(dur)//2],'p90',dur[int(len(dur)*.9)],'max',dur[-1])
    print(' goods waited at head:',collections.Counter(g[11].split('.')[-1] for g in S.values()).most_common(8))
    print(' where the pred sits in its holder queue:',collections.Counter((g[16] or '').split('#')[0]+('#0' if g[16]=='queue#0' else '') for g in S.values()).most_common(5))
    return S,cycles
for n in sys.argv[1:]:
    S,cycles=analyse(f'tests/out/bench/film/review/{n}/large-colony.server.log'); print()
