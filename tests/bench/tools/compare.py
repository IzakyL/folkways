import json,csv,collections,math,re,sys,statistics as st
def run(R):
    r=json.load(open(R+'large-colony.json')); roster=r['setup']['roster']; s,e=r['run']['start'],r['run']['end']; T=e-s
    out={'ticks':T}
    act=collections.Counter(); dist=collections.Counter()
    for x in csv.DictReader(open(R+'large-colony.exertion.csv')):
        if s<int(x['game_time'])<=e: act[x['body']]+=int(x['active_ticks']); dist[x['body']]+=float(x['distance'])
    out['active %']=round(sum(act.values())/len(roster)/T*100,2)
    out['blocks walked/body']=round(sum(dist.values())/len(roster))
    steps=collections.Counter(); seqs=collections.defaultdict(list)
    for x in csv.DictReader(open(R+'large-colony.work.csv')):
        if not s<int(x['game_time'])<=e: continue
        steps[x['step']]+=1
        if x['step']=='STARTED' and x['worker']: seqs[x['worker']].append(int(x['game_time']))
    out['started']=steps['STARTED']; out['finished']=steps['FINISHED']; out['released']=steps['RELEASED']; out['failed']=steps['FAILED']
    b=sorted((len(seqs.get(w,[])) for w,t in roster.items() if t=='builder'),reverse=True)
    out['builder starts median']=b[len(b)//2]
    out['top10% builders share %']=round(100*sum(b[:len(b)//10])/max(1,sum(b)))
    out['bottom half builders share %']=round(100*sum(b[len(b)//2:])/max(1,sum(b)))
    cs=[json.loads(l) for l in open(R+'large-colony.census.ndjson')]
    cs=[c for c in cs if s<c['game_time']<=e]
    for k in ('stalled','walking','working','idle'):
        out[k+' mean']=round(st.mean(c['standing'].get(k,0) for c in cs))
    out['stalled at end']=cs[-1]['standing'].get('stalled',0)
    out['stalled series']=[c['standing'].get('stalled',0) for c in cs[::60]]
    out['unassigned at end']={k:v for k,v in cs[-1]['unassigned'].items()}
    out['planned at end']=cs[-1]['planned']
    lines=open(R+'large-colony.server.log',errors='replace').read().split('\n')
    sc=[int(m.group(1))/1e6 for m in (re.search(r'schedule timing .*schedule_us=(\d+)',l) for l in lines) if m]
    out['schedules']=len(sc); out['schedule s med/p90/max']=(round(st.median(sc),2),round(sorted(sc)[int(len(sc)*.9)],2),round(max(sc),2)) if sc else None
    hb=[i for i,l in enumerate(lines) if 'labor heartbeat' in l]
    blk=[l for l in lines[hb[-1]:] if 'stall body=' in l]
    pos=sorted(int(m.group(1)) for m in (re.search(r'held=\w+:queue#(\d+)',l) for l in blk) if m)
    out['last dump: stalls']=len(blk)
    if pos: out['pred queue pos med/p90/max']=(pos[len(pos)//2],pos[int(len(pos)*.9)],pos[-1])
    d=sorted(int(m.group(1)) for m in (re.search(r' for=(\d+)t',l) for l in blk) if m)
    if d: out['stalled for med/p90']=(d[len(d)//2],d[int(len(d)*.9)])
    rows=[int(x['full_ns'])/1e6 for x in csv.DictReader(open(R+'large-colony.ticks.csv')) if s<int(x['game_time'])<=e]
    out['mspt mean/p95']=(round(st.mean(rows),1),round(sorted(rows)[int(len(rows)*.95)],1))
    out['build']=json.dumps(r['end_state'].get('build'))[:400]
    return out
a=run('tests/out/bench/reports.baseline/'); b=run('tests/out/bench/reports/')
for k in a: print(f'{k:32s} | {str(a[k]):60s} | {b.get(k)}')
