#!/usr/bin/env python3
# Phase 10 E2: writes the MLBB evaluation sets (app/src/main/assets/eval/mlbb_*.json). Edit the lists below, raise a
# set's "version" when its questions or answers change, then run:
#   python3 tools/eval-sets/build.py app/src/main/assets/eval
# MlbbEvalSetsTest checks the result (exact prompts, real heroes and roles, parsable plans).
import json, os, sys
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
ROLE = {}  # filled from names below (must match HeroDb; a unit test checks)
R = {"Layla":"marksman","Tigreal":"tank","Fanny":"assassin","Eudora":"mage","Miya":"marksman","Balmond":"fighter","Alucard":"fighter",
"Lesley":"marksman","Khufra":"tank","Lancelot":"assassin","Nana":"mage","Granger":"marksman","Franco":"tank","Chou":"fighter",
"Angela":"support","Hayabusa":"assassin","Karrie":"marksman","Esmeralda":"mage","Kagura":"mage","Gusion":"assassin","Moskov":"marksman",
"Grock":"tank","Zilong":"fighter","Beatrix":"marksman","Yu Zhong":"fighter","Harith":"mage","Atlas":"tank","Clint":"marksman",
"Ling":"assassin","Valir":"mage","Estes":"support","Hanabi":"marksman","Paquito":"fighter","Brody":"marksman","Saber":"assassin",
"Aldous":"fighter","Cyclops":"mage","Belerick":"tank"}
RANGED = {"marksman","mage","support"}
MELEE = {"Esmeralda","Alice","Guinevere","Karina","Gusion","Kaja","Kadita"}  # HeroDb.meleeExtra
LANE = {"gold":"Gold lane","exp":"EXP lane","mid":"Mid lane","roam":"Roam","jungle":"Jungle"}
OPTS = ["ALL_IN","POKE","FARM","RETREAT","GROUP","RULES"]
def tprompt(summary):
    return ("You play Mobile Legends: Bang Bang. Choose the best action for this fight moment.\n" + summary +
            "\nOptions: ALL_IN, POKE, FARM, RETREAT, GROUP, RULES\nAnswer with one option only.")
def tsum(hero, lane, t, hp, lvl, ready, spell, enemies, reach, allies, minions, tower, missing, scores):
    role = R[hero]
    s = f"hero {hero} ({role}, {'ranged' if role in RANGED and hero not in MELEE else 'melee'}), {LANE[lane]}, time {t}"
    s += f"\nme: HP {hp}%, level {lvl}, ready: {ready if ready else 'none'}"
    if spell: s += f", spell {spell[0]} {'ready' if spell[1] else 'used'}"
    s += f"\nenemies close: {len(enemies)}"
    if enemies: s += " (HP " + " ".join(f"{e}%" for e in enemies) + ")"
    s += ", nearest " + ("-" if not enemies else ("in reach" if reach else "outside reach"))
    s += f"\nallies close: {allies}, minions: {minions}, under enemy tower: {'yes' if tower else 'no'}, enemies missing from map: {missing}"
    if scores: s += "\nscores: attack %.2f, retreat %.2f, risk %.2f" % scores
    return s
F = ("Flicker", True); Fu = ("Flicker", False)
T = [
("t01","Layla","gold","6:10",15,7,"s1",Fu,[80,90],1,0,2,0,1,None,["RETREAT"],["GROUP"],["ALL_IN","FARM"],"15% HP, two healthy enemies in reach, no allies and Flicker used: leave now."),
("t02","Layla","gold","4:30",90,5,"s1 s2 ult",F,[20],1,1,3,0,0,None,["ALL_IN"],["POKE"],[],"A lone enemy at 20% in reach, full HP and every skill ready: take the kill."),
("t03","Tigreal","roam","8:00",85,9,"s1 s2 ult",F,[70,60,50],1,3,4,0,0,None,["ALL_IN"],["GROUP","RULES"],["FARM"],"The tank has ultimate and Flicker with three allies against three grouped, hurt enemies: start the fight."),
("t04","Fanny","jungle","3:00",40,4,"s2",("Retribution",True),[100,100],0,0,0,0,2,None,["RETREAT"],["GROUP"],["ALL_IN"],"40% HP, no ultimate, two full-HP enemies coming and two more missing."),
("t05","Eudora","mid","2:30",30,3,"s1",F,[90],1,0,0,1,0,None,["RETREAT"],["GROUP"],["ALL_IN"],"30% HP under the enemy tower with no minions to take its shots, against a healthy enemy."),
("t06","Miya","gold","12:00",70,12,"s1 s2 ult",("Purify",True),[60,55],1,3,5,0,0,(0.65,0.25,0.35),["POKE"],["ALL_IN","RULES"],[],"A marksman in a team fight with three allies: keep hitting from range."),
("t07","Balmond","exp","5:00",55,6,"s1 ult",("Petrify",True),[40],1,0,4,0,0,(0.60,0.30,0.30),["ALL_IN"],["RULES"],[],"Balmond's ultimate finishes a 40% enemy in reach; no one else is around."),
("t08","Alucard","exp","1:40",100,2,"s1 s2",("Execute",True),[95],0,0,6,0,0,None,["FARM"],["RULES","POKE"],[],"Level 2, the enemy is healthy and out of reach: take the minion wave."),
("t09","Lesley","gold","15:30",25,13,"s2 ult",F,[100,90,80],0,1,2,0,2,None,["RETREAT"],["GROUP"],["ALL_IN","FARM"],"25% HP, three healthy enemies coming and two missing: get out."),
("t10","Khufra","roam","9:20",90,10,"s1 s2 ult",F,[45,100],1,2,3,0,0,None,["ALL_IN"],["GROUP","RULES"],[],"A healthy tank with every skill and two allies against two enemies, one hurt: engage."),
("t11","Lancelot","jungle","7:40",80,9,"s1 s2 ult",("Retribution",True),[25],1,0,0,0,3,None,["ALL_IN"],["RULES"],[],"A 25% enemy alone in reach of an assassin with every skill: a quick kill."),
("t12","Lancelot","jungle","7:40",80,9,"",("Retribution",True),[25,100,100],1,0,0,0,0,None,["RETREAT"],["GROUP"],["ALL_IN"],"Every skill on cooldown and two healthy enemies next to the hurt one: an assassin without skills can't fight."),
("t13","Nana","mid","10:00",50,10,"s1 s2 ult",Fu,[70],0,1,4,0,2,None,["POKE"],["RULES"],["ALL_IN"],"A mage at half HP, Flicker used, two enemies missing: hit from range, don't dive."),
("t14","Granger","gold","3:10",100,4,"s1 s2 ult",None,[100],0,0,5,0,1,None,["FARM"],["POKE","RULES"],[],"Early game, both healthy, the enemy out of reach: farm the wave."),
("t15","Franco","roam","6:00",70,7,"s1 ult",F,[50],0,2,2,0,0,None,["ALL_IN"],["RULES"],[],"Franco has his hook and ultimate with two allies, and a lone enemy at half HP: catch it."),
("t16","Chou","exp","11:00",20,11,"s2",F,[60],1,0,3,1,1,None,["RETREAT"],["GROUP"],["ALL_IN"],"20% HP under the enemy tower: back off."),
("t17","Angela","roam","13:00",90,12,"s1 s2 ult",None,[80,70],1,2,3,0,0,None,["GROUP"],["POKE","RULES"],[],"A support stays with her allies in a fight."),
("t18","Hayabusa","jungle","16:00",65,14,"s1 s2 ult",("Retribution",True),[30,35],1,1,0,0,0,(0.70,0.20,0.30),["ALL_IN"],["RULES"],[],"Two hurt enemies in reach, every skill ready and an ally: Hayabusa's ultimate cleans up."),
("t19","Karrie","gold","18:30",45,14,"s1 s2 ult",("Purify",True),[90,85,80,75],0,0,3,0,1,None,["RETREAT"],["GROUP"],["ALL_IN","FARM"],"Four enemies coming at a lone marksman at 45%."),
("t20","Esmeralda","exp","8:30",75,9,"s1 s2 ult",F,[55],1,0,3,0,0,None,["ALL_IN"],["RULES","POKE"],[],"A one-on-one with more HP and every skill ready."),
("t21","Kagura","mid","5:30",35,6,"s2",F,[100],1,0,2,0,1,None,["RETREAT"],["GROUP"],["ALL_IN"],"A healthy enemy is on a 35% mage without her ultimate."),
("t22","Gusion","jungle","9:50",70,10,"s1 s2 ult",None,[10],1,0,0,1,0,None,["ALL_IN"],["POKE"],[],"A 10% enemy in reach: one quick combo kills it even under the tower at this level."),
("t23","Moskov","gold","7:00",60,8,"s1 s2 ult",None,[100,100],1,3,4,0,0,None,["POKE"],["GROUP","RULES"],[],"A marksman with three allies: keep hitting from behind them."),
("t24","Grock","roam","2:20",100,3,"s1 s2",F,[100],0,1,6,0,2,None,["GROUP"],["RULES"],[],"A roaming tank stays with the ally he is helping."),
("t25","Zilong","exp","20:00",50,15,"s1 s2 ult",("Execute",True),[15],1,0,0,0,0,None,["ALL_IN"],["RULES"],[],"A 15% enemy alone in reach, with Execute ready."),
("t26","Beatrix","gold","9:00",30,9,"s1 s2 ult",F,[100],1,1,2,0,0,None,["RETREAT"],["GROUP"],["FARM"],"A healthy assassin on a 30% marksman: get away, towards the ally."),
("t27","Yu Zhong","exp","12:30",80,12,"s1 s2 ult",("Petrify",True),[60,50,70],1,3,2,0,0,(0.70,0.20,0.30),["ALL_IN"],["RULES","GROUP"],[],"A healthy fighter with every skill and three allies against hurt enemies."),
("t28","Harith","mid","6:40",20,7,"s1",F,[50],1,0,2,1,0,None,["RETREAT"],["GROUP"],["ALL_IN"],"20% HP under the enemy tower."),
("t29","Atlas","roam","14:00",85,12,"s1 s2 ult",F,[90,80,70,60,85],1,4,3,0,0,None,["ALL_IN"],["RULES"],[],"Five enemies grouped in reach, ultimate and Flicker ready, four allies: Atlas's ultimate fight."),
("t30","Clint","gold","1:30",95,2,"s1 s2",None,[70],1,0,5,0,1,None,["POKE"],["FARM","RULES"],[],"Early lane: hit the hurt enemy from range between minions."),
("t31","Ling","jungle","11:10",55,11,"s1 s2 ult",("Retribution",True),[100,100,90],1,0,0,0,2,None,["RETREAT"],["GROUP"],["ALL_IN"],"Three healthy enemies on a 55% assassin."),
("t32","Valir","mid","8:20",90,9,"s1 s2 ult",None,[35],1,0,4,0,0,None,["ALL_IN"],["POKE"],[],"A 35% enemy alone in reach of a healthy mage with every skill."),
("t33","Estes","roam","17:00",70,13,"s1 s2 ult",None,[60,50],1,3,2,0,0,None,["GROUP"],["RULES","POKE"],[],"A healer stays with the team."),
("t34","Hanabi","gold","22:00",100,15,"s1 s2 ult",None,[100],0,0,6,0,4,None,["GROUP"],["RETREAT"],["ALL_IN"],"Late game, four enemies missing from the map: a lone marksman goes back to the team."),
("t35","Paquito","exp","3:40",85,4,"s1 s2 ult",("Petrify",True),[45],1,0,4,0,0,(0.66,0.22,0.28),["ALL_IN"],["POKE","RULES"],[],"Early lane, more HP, every skill against a 45% enemy."),
("t36","Brody","gold","10:30",40,10,"s2 ult",None,[100,100],1,0,3,1,0,None,["RETREAT"],["GROUP"],["ALL_IN"],"Two healthy enemies on a 40% marksman under their tower."),
("t37","Saber","roam","5:20",95,6,"s1 s2 ult",F,[55],1,0,0,0,0,None,["ALL_IN"],["RULES"],[],"A lone enemy at 55%: Saber's ultimate locks it."),
("t38","Aldous","exp","25:00",90,15,"s1 ult",None,[30],0,0,0,0,0,None,["ALL_IN"],["RULES"],[],"A 30% enemy alone: Aldous's ultimate reaches it from far away."),
("t39","Cyclops","mid","4:50",50,5,"s1 s2",F,[50],1,0,3,0,0,None,["POKE"],["RULES","ALL_IN"],[],"An even mid lane without the ultimate: trade from range."),
("t40","Belerick","exp","13:40",60,12,"s1 s2 ult",("Vengeance",True),[80,75],1,2,2,0,0,None,["ALL_IN"],["GROUP","RULES"],[],"The tank's taunt with two allies close starts the fight."),
]
items=[]
for (i,h,l,t,hp,lv,rd,sp,en,rc,al,mi,tw,ms,sc,best,ok,un,why) in T:
    s = tsum(h,l,t,hp,lv,rd,sp,en,rc,al,mi,tw,ms,sc)
    items.append({"id":i,"kind":"choice","summary":s,"prompt":tprompt(s),"options":OPTS,"best":best,"ok":ok,"unsafe":un,"why":why})
def save(name, obj):
    json.dump(obj, open(os.path.join(OUT,name),"w"), indent=1, ensure_ascii=False)
save("mlbb_tactical.json", {"schema":1,"id":"mlbb-tactical","version":1,"game":"mlbb","role":"tactical","reviewed":False,"maxTokens":16,
  "about":"Fight moments in the exact form the tactical model is asked (TacticalAdvisor.summary/prompt). best = the expected option, ok = also acceptable, unsafe = an answer that could get the hero killed; anything else is wrong.","items":items})

# strategic: plan items in the exact MlbbStrategy.strategyPrompt form, then open questions with a rubric
PLAN_TAIL = "Do not think; reply JSON only: {\"plan\":\"farm|push|fight|retreat|group|defend\",\"aggression\":0.5,\"focus\":\"weakest|nearest\",\"note\":\"max 6 words\"}"
def sprompt(hero, lane, t, hp, en, allies, minions, mm, extra="", items=None, gold=None):
    role = R.get(hero, "fighter")
    st = f"phase=in game, my HP {hp}%, enemy heroes on screen {len(en)}"
    if en: st += " (HP " + "/".join(f"{e}%" for e in en) + ")"
    st += f", allies near {allies}, enemy minions {minions}, enemies on minimap {mm}{extra}, lane {LANE[lane]}, match time {t}"
    if items is not None: st += f", items {items}"
    if gold is not None: st += f", gold {gold}"
    return f"MLBB strategy for {hero} ({role}, {LANE[lane]}). State: {st}. Time {t}. " + PLAN_TAIL
P = [
("s01","Layla","gold","3:00",100,[],0,4,2,"",1,300,["farm"],["push"],["fight"],"Early game, nobody near, a wave to farm: farm."),
("s02","Tigreal","roam","8:05",90,[],4,0,2,", enemies dead 3 (alive 2), allies dead 0, lord HP 100%",None,None,["push"],["fight","group"],["retreat"],"Three enemies dead with the whole team together: push (or take the lord)."),
("s03","Miya","gold","14:00",30,[90,80,100],0,2,3,"",4,800,["retreat"],["group"],["fight","push"],"30% HP and three healthy enemies on screen alone."),
("s04","Balmond","exp","10:00",80,[],0,6,0,"",3,500,["push"],["farm"],[],"A big wave and no enemy anywhere on the map: push the tower."),
("s05","Khufra","roam","12:00",90,[40,50],3,3,2,"",3,400,["fight"],["group"],["retreat"],"A healthy tank with three allies against two hurt enemies."),
("s06","Layla","gold","18:00",60,[],3,0,5,", lord HP 80%",5,900,["group"],["fight","defend"],["farm"],"The enemies are at the lord: join the team to contest it."),
("s07","Gusion","jungle","7:00",100,[],0,0,1,", enemies dead 2 (alive 3), allies dead 0",3,600,["push"],["farm","fight"],["retreat"],"Two enemies dead: use the time to push."),
("s08","Moskov","gold","20:00",40,[],0,0,0,", enemies dead 0 (alive 5), allies dead 3",5,1200,["defend"],["retreat","group"],["push","fight"],"Three allies dead and every enemy hidden: hold the base."),
("s09","Nana","mid","1:00",100,[100],0,5,1,"",1,150,["farm"],["push"],[],"Level 1 mid lane: farm the wave."),
("s10","Chou","exp","9:00",15,[60],0,3,1,"",3,600,["retreat"],["group"],["fight","push"],"15% HP: go back."),
("s11","Angela","roam","15:00",90,[70,60,80],3,2,3,"",4,500,["group"],["fight"],["farm"],"A support in a team fight stays with the team."),
("s12","Hayabusa","jungle","16:00",100,[],0,5,2,"",5,700,["push"],["farm"],[],"A healthy assassin with a wave and the enemies far away: split push."),
("s13","Esmeralda","exp","4:00",70,[30],1,3,1,"",2,300,["fight"],["push"],["retreat"],"A 30% enemy and an ally: fight."),
("s14","Clint","gold","23:00",100,[],0,4,0,", enemies dead 0 (alive 5), allies dead 2",6,1500,["group"],["defend","retreat"],["push"],"Late game, two allies dead and every enemy hidden: a lone marksman must not push."),
("s15","Atlas","roam","11:00",80,[80,85,80,75,80],4,2,5,"",3,300,["fight"],["group"],["farm"],"Five enemies grouped in front of a full team: Atlas engages."),
]
sitems=[]
for (i,h,l,t,hp,en,al,mi,mm,ex,it,gd,best,ok,un,why) in P:
    sitems.append({"id":i,"kind":"plan","prompt":sprompt(h,l,t,hp,en,al,mi,mm,ex,it,gd),"field":"plan",
                   "options":["farm","push","fight","retreat","group","defend"],"best":best,"ok":ok,"unsafe":un,"why":why})
COACH = "You coach a Mobile Legends: Bang Bang player. {q} Answer in at most three sentences."
JUNGLERS = ["Ling","Fanny","Lancelot","Hayabusa","Gusion","Karina","Saber","Natalia","Aamon","Benedetta","Yi Sun-shin","Fredrinn",
  "Barats","Baxia","Akai","Balmond","Alucard","Martis","Roger","Leomord","Freya","Yin","Joy","Nolan","Hanzo","Helcurt","Harley",
  "Julian","Alpha","Dyrroth","Aulus","Thamuz","Hilda","Bane","Suyou"]
Q = [
("r01","The enemy has Fanny, Ling and Lancelot, three assassins that dash a lot. Name one hero for our last pick that counters them, and why.",
 [["Khufra","Minsitthar","Kaja","Chou","Saber","Franco","Phoveus"],["dash","blink","mobility","lock","stun","suppress","crowd control","control","stop"]],[],
 "Khufra, Minsitthar, Kaja and Phoveus punish or stop dashes; Chou, Saber and Franco lock a single target."),
("r02","When does the Lord first appear, and when should a team take it?",
 [["8:00","8 min","eight min","8th min","minute 8"],["enemies dead","enemy is dead","enemies are dead","won a fight","win a fight","after a team fight","advantage","numbers","pick"]],[],
 "The Lord appears at 8:00; take it when enemies are dead or the team has the numbers."),
("r03","Our team is 5,000 gold behind at 12 minutes. What should we do?",
 [["defend","turret","tower","safe"],["together","group","as a team"],["pick","catch","mistake","wait","late game","scale","objective"]],["dive","split push alone"],
 "Behind: defend towers together and wait for a pick or a mistake; never dive or split alone."),
("r04","What does the Turtle give the team, and when does it first appear?",
 [["gold","experience","exp","shield"],["2:00","2 min","two min","minute 2"]],[],
 "The Turtle gives gold and experience (and a shield to the one who kills it); it first appears at 2:00."),
("r05","The enemy has three magic damage heroes: Eudora, Kagura and Lunox. Which defensive item should our tank buy first?",
 [["Athena's Shield","Athena","Radiant Armor","Oracle"]],["Antique Cuirass","Blade Armor","Dominance Ice"],
 "Magic defence (Athena's Shield, Radiant Armor, Oracle); the physical defence items are wrong here."),
("r06","The enemy has Estes and Esmeralda, who heal a lot. What should our marksman buy against them?",
 [["Sea Halberd","anti-heal","antiheal","healing reduction","reduce healing","reduces healing"]],[],
 "Healing reduction (Sea Halberd for a marksman)."),
("r07","As a roamer at 1:30, after helping the gold lane, where should you go next and why?",
 [["mid","turtle","jungle","EXP"],["vision","scout","help","gank","objective","rotate"]],[],
 "Rotate to mid, the jungle or the turtle to give vision and help."),
("r08","The enemy marksman is fed with 8 kills. As our tank, what is your job in team fights?",
 [["marksman","carry","backline"],["protect","peel","stun","lock","engage","initiate","focus","catch"]],[],
 "Lock down or keep away the fed marksman."),
("r09","The enemy jungler has been missing from the map for 20 seconds. What should an EXP laner do?",
 [["back","careful","safe","retreat","tower","bush","ward","cautious"],["missing","jungler","gank","ambush"]],["dive the tower","tower dive"],
 "Play safe near the tower and expect a gank; never dive."),
("r10","Our team has Tigreal, Lunox and Granger. Which jungler should we pick to fit, and why?",
 [JUNGLERS,["burst","damage","physical","engage","mobility","objective","Retribution","jungle"]],[],
 "Any real jungler with a reason (the team lacks a physical jungler)."),
("r11","Why should a team not chase into the fog after winning a fight?",
 [["ambush","bush","vision","fog","unseen","respawn","trap"],["objective","tower","turtle","lord","push"]],[],
 "Chasing blind risks an ambush; take an objective instead."),
("r12","When should a jungler use Retribution?",
 [["turtle","lord","buff","creep","monster"],["steal","secure","last hit","finish","kill"]],[],
 "To secure the last hit on the turtle, lord or buffs."),
("r13","The enemy has Khufra and Atlas, who stun and pull. Which battle spell helps our marksman most?",
 [["Purify"]],[],
 "Purify removes the crowd control."),
("r14","At 18 minutes the enemy base turret is open and two enemies are dead for 25 seconds. What should we do?",
 [["push","base","turret","throne","end"]],["farm the jungle","go back to farm"],
 "Push the base and end."),
("r15","Our gold laner keeps dying to ganks. Give two pieces of advice.",
 [["ward","vision","map","minimap"],["bush","tower","safe","back","position","positioning"]],[],
 "Watch the minimap and play nearer the tower."),
]
for (i,q,must,mustNot,why) in Q:
    sitems.append({"id":i,"kind":"rubric","prompt":COACH.format(q=q),"must":must,"mustNot":mustNot,"why":why})
save("mlbb_strategic.json", {"schema":1,"id":"mlbb-strategic","version":1,"game":"mlbb","role":"strategic","reviewed":False,"maxTokens":160,
  "about":"Plan items are asked in the exact form the strategic model is asked (MlbbStrategy.strategyPrompt) and scored on the JSON plan; rubric items are coaching questions scored by the points they must name (each list is alternatives) and the claims they must not make.","items":sitems})

REVIEW = "You coach a Mobile Legends: Bang Bang player after a match. Match: {m} Write a short review: what went wrong or right, and one thing to do next time. At most four sentences."
V = [
("v01","Hero Layla, gold lane, defeat, 6 kills 9 deaths 4 assists. 5 of the 9 deaths were alone in the enemy jungle after 12:00. The enemy took the lord twice.",
 [["alone","solo","without the team","without your team","isolated","by yourself"],["jungle","position","enemy side","fog","vision"],["lord"]],[]),
("v02","Hero Tigreal, roam, victory, 1 kill 4 deaths 18 assists. Started 4 team fights with ultimate and Flicker and won 3 of them. Few vision wards.",
 [["engage","initiat","ultimate","fight","Flicker"],["vision","ward","map","scout"]],[]),
("v03","Hero Fanny, jungle, defeat, 2 kills 8 deaths 3 assists. Energy was often empty in fights. The enemy took the turtle 3 times out of 3.",
 [["energy","blue","cable"],["turtle","objective"]],[]),
("v04","Hero Miya, gold lane, victory, 12 kills 3 deaths 7 assists. Most damage in the match. Items: Windtalker, Demon Hunter Sword, Corrosion Scythe, Golden Staff. All 3 deaths came from Saber's ultimate.",
 [["Saber","assassin","burst","ultimate"],["Wind of Nature","Purify","defensive","Immortality","Athena","Rose Gold Meteor","defence","defense"]],[]),
("v05","Hero Eudora, mid lane, defeat, 3 kills 6 deaths 2 assists. Low damage. Took no part in 6 of 9 team fights. 30 minions farmed by 10:00.",
 [["team fight","rotate","rotation","join","help","group"],["farm","minion","wave"]],[]),
("v06","Hero Chou, EXP lane, victory, 8 kills 5 deaths 10 assists. Lost the EXP lane tower at 5:00 while ganking mid; later carried the fights.",
 [["tower","lane"],["gank","rotation","timing","wave"]],[]),
("v07","Hero Lesley, gold lane, defeat, 4 kills 2 deaths 1 assist. Few deaths but only 12% of the team's damage. Stayed in lane while the team fought for the lord at 10:00, 14:00 and 18:00.",
 [["lord","objective"],["group","join","team","rotate"]],[]),
("v08","Hero Angela, roam, victory, 0 kills 2 deaths 22 assists. Ultimate used on the marksman in 7 fights; healed 30,000.",
 [["ultimate","heal","marksman","carry","protect","support"]],[]),
("v09","Hero Balmond, jungle, defeat, 5 kills 7 deaths 6 assists. Retribution missed on the lord at 16:30 and the enemy stole it; after that the enemy pushed to the base.",
 [["Retribution","steal","smite","last hit"],["lord"]],[]),
("v10","Hero Kagura, mid lane, defeat, 1 kill 7 deaths 4 assists. Died 5 times to Lancelot. Bought no defensive item. Used Flicker to chase.",
 [["Lancelot","assassin"],["defensive","Winter","Athena","Immortality","item","defence","defense"],["Flicker","escape","spell"]],[]),
]
vitems=[{"id":i,"kind":"rubric","prompt":REVIEW.format(m=m),"must":must,"mustNot":mn,"why":"The review must name the points the match shows."} for (i,m,must,mn) in V]
save("mlbb_review.json", {"schema":1,"id":"mlbb-review","version":1,"game":"mlbb","role":"strategic","reviewed":False,"maxTokens":200,
  "about":"Short match summaries; a good review names the points each lists (each list is alternatives).","items":vitems})
print(len(items), len(sitems), len(vitems))
