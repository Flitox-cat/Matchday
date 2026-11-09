import csv, math, random
rows=list(csv.DictReader(open('/tmp/t25.csv')))+list(csv.DictReader(open('/tmp/t26.csv')))
rows=[r for r in rows if r['score'] and 'W/O' not in r['score'].upper()]
rows.sort(key=lambda r:(r['tourney_date'],int(r['match_num'] or 0)))
def setwin(ha,hb):
    def st(af):
        dp=[[0]*8 for _ in range(8)];dp[0][0]=1;win=0;tb=min(.95,max(.05,.5+(ha-hb)*.9))
        for tot in range(14):
            for a in range(min(tot,7)+1):
                b=tot-a
                if b<0 or b>7: continue
                p=dp[a][b]
                if not p: continue
                if ((a>=6 or b>=6) and abs(a-b)>=2) or a==7 or b==7:
                    if a>b: win+=p
                    continue
                if a==6 and b==6: dp[7][6]+=p*tb; dp[6][7]+=p*(1-tb); continue
                pa=ha if (tot%2==0)==af else 1-hb
                dp[a+1][b]+=p*pa; dp[a][b+1]+=p*(1-pa)
        return win
    return (st(True)+st(False))/2
elo={};n={};sv={};
def g(k,rank):
    if k not in elo: elo[k]=1400 if not rank else 2050-100*math.log(rank)
    return elo[k]
res={'rank':[],'elo':[],'serve':[],'blend':[],'blend_rank':[]}
held=1;svt=1
for i,r in enumerate(rows):
    w,l=r['winner_name'],r['loser_name']
    try: wr=int(float(r['winner_rank'])); 
    except: wr=None
    try: lr=int(float(r['loser_rank']))
    except: lr=None
    # randomize orientation
    flip=random.random()<.5
    a,b,ar,br=(w,l,wr,lr) if not flip else (l,w,lr,wr)
    y=0 if flip else 1
    test=i>len(rows)*0.4
    ea,eb=g(a,ar),g(b,br)
    pe=1/(1+10**((eb-ea)/400))
    pr=1/(1+math.exp(-0.62*(math.log(br)-math.log(ar)))) if ar and br else None
    ps=None
    sa,sb=sv.get(a),sv.get(b)
    avg=held/svt
    if sa and sb and sa[0]>=40 and sb[0]>=40 and sa[2]>=40 and sb[2]>=40:
        ha=sa[1]/sa[0]; hb=sb[1]/sb[0]; ba=sa[3]/sa[2]; bb=sb[3]/sb[2]
        x=min(.97,max(.35,ha-(bb-(1-avg)))); z=min(.97,max(.35,hb-(ba-(1-avg))))
        s=setwin(x,z); ps=s*s*(3-2*s)
    if test and pr is not None and ps is not None:
        res['rank'].append((pr,y)); res['elo'].append((pe,y)); res['serve'].append((ps,y))
        res['blend'].append(((1.5*pe+ps)/2.5,y))
        res['blend_rank'].append(((pe+pr)/2,y))
    if 'RET' not in r['score'].upper():
        nw=n.get(w,0); nl=n.get(l,0)
        ew,el=g(w,wr),g(l,lr); e=1/(1+10**((el-ew)/400))
        elo[w]=ew+125/((nw+5)**.4)*(1-e); elo[l]=el-125/((nl+5)**.4)*(1-e); n[w]=nw+1;n[l]=nl+1
    try:
        wS,wF,wSv,lS,lF,lSv=[int(float(r[c])) for c in ('w_bpSaved','w_bpFaced','w_SvGms','l_bpSaved','l_bpFaced','l_SvGms')]
        if wSv>0 and lSv>0:
            wh=wSv-(wF-wS); lh=lSv-(lF-lS)
            for k,(s1,h1,r1,b1) in ((w,(wSv,wh,lSv,lSv-lh)),(l,(lSv,lh,wSv,wSv-wh))):
                o=sv.get(k,[0,0,0,0]); sv[k]=[o[0]+s1,o[1]+h1,o[2]+r1,o[3]+b1]
            held+=wh+lh; svt+=wSv+lSv
    except: pass
for k,v in res.items():
    br=sum((p-y)**2 for p,y in v)/len(v); ll=-sum(math.log(p if y else 1-p) for p,y in v)/len(v); acc=sum((p>.5)==(y==1) for p,y in v)/len(v)
    print(f"{k:11s} n={len(v)} brier={br:.4f} logloss={ll:.4f} acc={acc:.3f}")
# calibration of blend
for lo in (0.5,0.6,0.7,0.8,0.9):
    v=[(p,y) for p,y in res['blend'] if lo<=max(p,1-p)<lo+.1]
    if v: print(lo, len(v), round(sum(max(p,1-p) for p,y in v)/len(v),3), round(sum((p>.5)==(y==1) for p,y in v)/len(v),3))
