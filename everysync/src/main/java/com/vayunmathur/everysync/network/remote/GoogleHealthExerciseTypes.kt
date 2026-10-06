package com.vayunmathur.everysync.network.remote

import androidx.health.connect.client.records.ExerciseSessionRecord

internal object GoogleHealthExerciseTypes {
    internal val MAP: Map<String, Int> = buildMap {
        fun putAll(type: Int, vararg names: String) {
            for (name in names) put(name, type)
        }
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, "BADMINTON")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL, "BASEBALL")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL, "BASKETBALL")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "BIKING", "OUTDOOR_BIKE")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
            "STATIONARY_BIKE",
            "ASSAULT_BIKE",
            "SPINNING",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP, "BOOTCAMP")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_BOXING, "BOXING")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, "CALISTHENICS")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_CRICKET, "CRICKET")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
            "DANCING",
            "BALLET",
            "BALLROOM_DANCE",
            "HIP_HOP",
            "JAZZ_DANCE",
            "MODERN_DANCE",
            "TANGO",
            "ZUMBA",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL, "ELLIPTICAL")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
            "EXERCISE_CLASS",
            "BARRE_CLASS",
            "CARDIO_SCULPT",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_FENCING, "FENCING")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN,
            "FOOTBALL_AMERICAN",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN,
            "FOOTBALL_AUSTRALIAN",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_GOLF, "GOLF")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING,
            "GUIDED_BREATHING",
            "MEDITATE",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS, "GYMNASTICS")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL, "HANDBALL")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            "HIIT",
            "INTERVAL_WORKOUT",
            "TABATA_WORKOUT",
            "CIRCUIT_TRAINING",
            "CROSSFIT",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, "HIKING")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY,
            "HOCKEY",
            "FIELD_HOCKEY",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING,
            "ICE_SKATING",
            "SPEED_SKATING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
            "MARTIAL_ARTS",
            "KARATE",
            "TAEKWONDO",
            "MUAY_THAI",
            "JIU_JITSU",
            "KICKBOXING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            "PADDLEBOARDING",
            "KAYAKING",
            "CANOEING",
            "ROWING",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, "ROWING_MACHINE")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING, "PARAGLIDING")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_PILATES, "PILATES")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL, "RACQUETBALL")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING,
            "ROCK_CLIMBING",
            "CLIMBING",
            "INDOOR_CLIMBING",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_RUGBY, "RUGBY")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "RUNNING",
            "TRAIL_RUN",
            "INCLINE_RUN",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, "TREADMILL")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SAILING, "SAILING", "FOILING")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING,
            "SCUBA_DIVING",
            "DIVING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_SKATING,
            "SKATING",
            "ROLLER_SKATING",
            "ROLLERBLADING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING,
            "SKIING",
            "CROSS_COUNTRY_SKI",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING, "SNOWBOARDING")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING, "SNOWSHOEING")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, "SOCCER")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL, "SOFTBALL")
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, "SQUASH")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
            "STAIRCLIMBER",
            "STAIR_CLIMBING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
            "STEP_TRAINING",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            "STRENGTH_TRAINING",
            "POWERLIFTING",
            "FUNCTIONAL_STRENGTH_TRAINING",
            "FREE_WEIGHTS",
            "WEIGHT_MACHINES",
            "CORE_TRAINING",
            "RESISTANCE_BANDS",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
            "STRETCHING",
            "TAI_CHI",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_SURFING, "SURFING")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            "SWIMMING_OPEN_WATER",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            "SWIMMING_POOL",
            "SWIMMING",
            "SYNCHRONIZED_SWIMMING",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, "TABLE_TENNIS")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
            "TENNIS",
            "PADEL",
            "PICKELBALL",
            "RACKET_SPORTS",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
            "VOLLEYBALL",
            "VOLLEYBALL_BEACH",
        )
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            "WALKING",
            "POWER_WALKING",
            "NORDIC_WALKING",
            "STROLLER_WALK",
            "WALK_WITH_WEIGHTS",
            "RUCKING",
            "TREADMILL_WALK",
            "INCLINE_WALK",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO, "WATER_POLO")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            "WEIGHTLIFTING",
            "WEIGHTS",
        )
        putAll(ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR, "WHEELCHAIR")
        putAll(
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            "YOGA",
            "YOGA_BIKRAM",
            "YOGA_HATHA",
            "YOGA_POWER",
            "YOGA_VINYASA",
        )
    }
}
