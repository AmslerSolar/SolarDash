// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.enphase

/**
 * Responses shaped like the samples in Enphase's technical brief "Accessing IQ Gateway local APIs or local UI with
 * token-based authentication" (January 2023), appendix. The brief's samples are used verbatim where possible;
 * [reading] emits one `/ivp/meters/readings` entry with exactly the brief's keys.
 */
object EnphaseFixtures {
    /** Appendix "Getting meter details", verbatim. */
    const val METERS_BRIEF = """[
 {"eid": 704643328, "state": "enabled", "measurementType": "production", "phaseMode": "split",
  "phaseCount": 2, "meteringStatus": "normal", "statusFlags": [ ]},
 {"eid": 704643584, "state": "enabled", "measurementType": "net-consumption", "phaseMode": "split",
  "phaseCount": 2, "meteringStatus": "normal", "statusFlags": [ ]}
]"""

    /** Production CT only; the consumption CT is installed but disabled. */
    const val METERS_PRODUCTION_ONLY = """[
 {"eid": 704643328, "state": "enabled", "measurementType": "production", "phaseMode": "split",
  "phaseCount": 2, "meteringStatus": "normal", "statusFlags": [ ]},
 {"eid": 704643584, "state": "disabled", "measurementType": "net-consumption", "phaseMode": "split",
  "phaseCount": 2, "meteringStatus": "normal", "statusFlags": [ ]}
]"""

    /** Microinverters only, no CTs configured. */
    const val METERS_NONE = "[]"

    @Suppress("LongParameterList")
    fun reading(
        eid: Long, dlvd: Double, rcvd: Double, power: Double, voltage: Double, current: Double,
        apparent: Double = 0.0, reactive: Double = 0.0, pf: Double = 0.0, channels: List<String>? = null,
    ): String = """{
"eid": $eid,
"timestamp": 1654218661,
"actEnergyDlvd": $dlvd, "actEnergyRcvd": $rcvd,
"apparentEnergy": 0.000, "reactEnergyLagg": 0.000, "reactEnergyLead": 0.000,
"instantaneousDemand": $power, "activePower": $power,
"apparentPower": $apparent, "reactivePower": $reactive,
"pwrFactor": $pf,
"voltage": $voltage,
"current": $current,
"freq": 59.188""" + (channels?.let { ",\n\"channels\": [${it.joinToString(",\n")}]" } ?: "") + "\n}"

    private val productionBrief = reading(
        704643328, 1608426.912, 4.923, 132.118, 246.377, 43.257, 5328.778, -5328.778, 0.025,
        listOf(
            reading(1778385169, 803639.138, 2.650, 66.037, 123.184, 21.622, 2663.476, -2663.476, 0.025),
            reading(1778385170, 804787.774, 2.273, 66.082, 123.193, 21.635, 2665.302, -2665.302, 0.025),
            reading(1778385171, 0.000, 0.000, 0.000, 0.000, 0.000),
        ),
    )

    private val netBrief = reading(
        704643584, 48540.732, 1244797.861, -0.000, 246.338, 0.283, 34.831, -0.000, 0.000,
        listOf(
            reading(1778385425, 24176.961, 600344.235, -0.000, 123.152, 0.137, 16.858),
            reading(1778385426, 24363.771, 644453.626, -0.000, 123.186, 0.146, 17.973),
            reading(1778385427, 129399.711, 93791.210, 0.000, 123.175, 21.902, 2697.761, 2697.761),
        ),
    )

    private val thirdBrief = reading(704643840, 258799.422, 187582.421, 0.000, 246.351, 43.804, 5395.521, 5395.521)

    /** Appendix "Getting meter readings": production, net-consumption and an eid not in the meter list. */
    val READINGS_BRIEF = "[\n$productionBrief,\n$netBrief,\n$thirdBrief\n]"

    val READINGS_PRODUCTION_ONLY = "[\n$productionBrief\n]"

    /** Same shape, sunny afternoon: 2500 W produced, 1800 W exported (net-consumption negative), 700 W used. */
    val READINGS_EXPORTING = "[\n" + reading(
        704643328, 1608426.912, 4.923, 2500.0, 230.0, 10.9,
        channels = listOf(reading(1778385169, 803639.138, 2.650, 1250.0, 230.1, 5.4), reading(1778385170, 804787.774, 2.273, 1250.0, 229.9, 5.5)),
    ) + ",\n" + reading(
        704643584, 48540.732, 1244797.861, -1800.0, 230.0, 7.8,
        channels = listOf(reading(1778385425, 24176.961, 600344.235, -1000.0, 230.2, 4.3), reading(1778385426, 24363.771, 644453.626, -800.0, 229.8, 3.5)),
    ) + "\n]"

    /** Appendix "Getting reported inverter production data", verbatim. */
    const val INVERTERS_BRIEF = """[
 {"serialNumber": "121935144671", "lastReportDate": 1654171836, "devType": 1, "lastReportWatts": 15, "maxReportWatts": 38},
 {"serialNumber": "121935144623", "lastReportDate": 1654171766, "devType": 1, "lastReportWatts": 5, "maxReportWatts": 5}
]"""

    /** Appendix "Getting meter's live data" (counters shortened). */
    const val LIVEDATA_BRIEF = """{
 "connection": {"mqtt_state": "connected", "prov_state": "configured", "auth_state": "ok", "sc_stream": "enabled", "sc_debug": "enabled"},
 "meters": {
  "last_update": 1654221647, "soc": 100, "main_relay_state": 0, "gen_relay_state": 5, "backup_bat_mode": 1,
  "backup_soc": 10, "is_split_phase": 1, "phase_count": 0, "enc_agg_soc": 100, "enc_agg_energy": 24800,
  "acb_agg_soc": 0, "acb_agg_energy": 0,
  "pv": {"agg_p_mw": 329549, "agg_s_mva": 329549, "agg_p_ph_a_mw": 329549, "agg_p_ph_b_mw": 0, "agg_p_ph_c_mw": 0,
         "agg_s_ph_a_mva": 329549, "agg_s_ph_b_mva": 0, "agg_s_ph_c_mva": 0},
  "storage": {"agg_p_mw": -220800, "agg_s_mva": -559446, "agg_p_ph_a_mw": -220800, "agg_p_ph_b_mw": 0, "agg_p_ph_c_mw": 0,
              "agg_s_ph_a_mva": -559446, "agg_s_ph_b_mva": 0, "agg_s_ph_c_mva": 0},
  "grid": {"agg_p_mw": 0, "agg_s_mva": 0, "agg_p_ph_a_mw": 0, "agg_p_ph_b_mw": 0, "agg_p_ph_c_mw": 0,
           "agg_s_ph_a_mva": 0, "agg_s_ph_b_mva": 0, "agg_s_ph_c_mva": 0},
  "load": {"agg_p_mw": 108749, "agg_s_mva": -229897, "agg_p_ph_a_mw": 108749, "agg_p_ph_b_mw": 0, "agg_p_ph_c_mw": 0,
           "agg_s_ph_a_mva": -229897, "agg_s_ph_b_mva": 0, "agg_s_ph_c_mva": 0},
  "generator": {"agg_p_mw": 0, "agg_s_mva": 0, "agg_p_ph_a_mw": 0, "agg_p_ph_b_mw": 0, "agg_p_ph_c_mw": 0,
                "agg_s_ph_a_mva": 0, "agg_s_ph_b_mva": 0, "agg_s_ph_c_mva": 0}
 },
 "tasks": {"task_id": 27672012, "timestamp": 1654219883},
 "counters": {"main_CfgLoad": 1, "main_CfgChanged": 1, "rest_Meters": 7, "rest_Status": 579}
}"""

    /** UNVERIFIED: `/info` is not in the brief; shaped like what community projects report. */
    const val INFO_XML = """<?xml version='1.0' encoding='UTF-8'?>
<envoy_info><time>1654221647</time><device><sn>122212345678</sn><pn>800-00654-r08</pn>
<software>D7.6.175</software><euaid>4c8675</euaid><seqnum>0</seqnum><apiver>1</apiver><imeter>true</imeter></device></envoy_info>"""
}
