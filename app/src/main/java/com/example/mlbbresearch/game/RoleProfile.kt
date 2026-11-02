package com.example.mlbbresearch.game

// ONE tactical engine for all five roles: a role is DATA, not separate code. Each profile says
// what the role is for, in which order it checks its jobs, and a few numbers that change its
// style. The engine (TacticalEngine) runs the same jobs for everyone, in the role's order.
// The hero on top of it comes from the hero adapter (HeroProfile + SkillBook: range, mobility,
// crowd control, damage, combos), so any hero can play any role.
enum class Job {
    STRONG_FIGHT,   // an enemy hero worth leaving everything for (engagement 70+)
    BOSS,           // turtle / lord on screen
    OBJECTIVE_MAP,  // turtle / lord up on the minimap: rotate there
    MONSTERS,       // jungle monsters on screen
    FIGHT,          // an enemy hero (engagement 50+)
    POSITIONING,    // not a good fight yet: keep a safe distance
    GANK,           // walk to an exposed enemy in a lane (minimap)
    JUNGLE_ROUTE,   // next jungle camp
    MINIONS,        // the wave (clear / freeze / push)
    TOWER,          // push the enemy tower with minions in (green circle)
    DEFEND,         // our tower under attack
    TEAMFIGHT,      // join a fight seen on the minimap
    LANE_CAMPS,     // a jungle camp near the lane between waves
    SPLIT_PUSH,     // push a side lane alone while the enemies are elsewhere
    ROAM,           // walk with mid / jungler, check bushes ahead (vision)
    SAFETY          // do not wander alone deep in the enemy half
}

class RoleProfile(
    val lane: Lane,
    val focus: String,                 // what the role is for (shown in the status)
    val order: List<Job>,              // the order the jobs are tried
    val retreatBonus: Float = 0f,      // added to the retreat HP (gold lane: careful)
    val rotateEarly: Float = 0.35f,    // how far (map units) it rotates to objectives / fights before 8:00
    val rotateLate: Float = 0.35f,     // and after
    val objectiveNeedsAlly: Boolean = true,
    val campRadiusEarly: Float = 0.22f,
    val campRadiusLate: Float = 0.15f
) {
    companion object {
        private val all = mapOf(
            Lane.EXP to RoleProfile(Lane.EXP, "lane pressure, engage, split push",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.BOSS, Job.OBJECTIVE_MAP, Job.TOWER, Job.FIGHT, Job.POSITIONING,
                    Job.TEAMFIGHT, Job.DEFEND, Job.LANE_CAMPS, Job.SPLIT_PUSH, Job.SAFETY),
                rotateEarly = 0.5f, rotateLate = 0.35f, objectiveNeedsAlly = false),
            Lane.MID to RoleProfile(Lane.MID, "wave clear, rotate, control",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.BOSS, Job.OBJECTIVE_MAP, Job.TEAMFIGHT, Job.TOWER, Job.FIGHT,
                    Job.POSITIONING, Job.DEFEND, Job.LANE_CAMPS, Job.SAFETY),
                rotateEarly = 0.5f, rotateLate = 0.45f, objectiveNeedsAlly = false),
            Lane.GOLD to RoleProfile(Lane.GOLD, "farm, positioning, survival",
                listOf(Job.STRONG_FIGHT, Job.MINIONS, Job.BOSS, Job.OBJECTIVE_MAP, Job.TOWER, Job.FIGHT, Job.POSITIONING,
                    Job.DEFEND, Job.LANE_CAMPS, Job.TEAMFIGHT, Job.SAFETY),
                retreatBonus = 0.06f, rotateEarly = 0.15f, rotateLate = 0.4f, campRadiusEarly = 0.25f, campRadiusLate = 0.18f),
            Lane.JUNGLE to RoleProfile(Lane.JUNGLE, "farm, gank, objectives",
                listOf(Job.STRONG_FIGHT, Job.BOSS, Job.OBJECTIVE_MAP, Job.MONSTERS, Job.FIGHT, Job.POSITIONING, Job.GANK,
                    Job.JUNGLE_ROUTE, Job.MINIONS, Job.TOWER, Job.SAFETY),
                rotateEarly = 0.45f, rotateLate = 0.45f, objectiveNeedsAlly = false),
            Lane.ROAM to RoleProfile(Lane.ROAM, "vision, initiation, protection",
                listOf(Job.BOSS, Job.OBJECTIVE_MAP, Job.FIGHT, Job.POSITIONING, Job.MINIONS, Job.TOWER, Job.DEFEND,
                    Job.TEAMFIGHT, Job.ROAM, Job.SAFETY),
                rotateEarly = 0.45f, rotateLate = 0.45f, objectiveNeedsAlly = false)
        )

        fun of(lane: Lane): RoleProfile = all[lane] ?: all.getValue(Lane.MID)
    }
}
