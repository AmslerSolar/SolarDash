// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.driver.abb

/**
 * Responses of a real ABB CDD (read 2026-11-02), shortened to 5 of its 16 microinverters: three reporting, one that
 * never answered and one bogus "xxxxxx" entry. Serial numbers, MAC addresses, location and boot id are changed;
 * layout, padding and every other value are as the CDD sent them.
 */
object AbbCddFixtures {
    private const val HEADER = """<?xml version="1.0" encoding="UTF-8" ?>
<data sunrise="07:11" sunset="17:08" lat="N46.00" lon="E008.00" timezone="Generic-GMT1" dst="0" ts="02-11-26 10:38:26" time="02-11-26 10:38" sun="DAY" weblanguage="ENGLISH" datalivequeue_start="212" datalivequeue_stop="212" datalivequeue_cnt="0" alarmsqueue_first="25" alarmsqueue_last="25" dhcpstatus="0" gateway="OK" upgrade_portal="OK" data_portal="OK" msgdatalive="13685" upgrequests="1113" micro="5" extra="0"  vFwUpgrade="NONE" wizard="0" acquisitionprocess="1">"""

    private const val CDD = """<cdd macrf="00:12:4B:00:00:00:00:01" sn="300001" wakeup="10:39:13 16/09/26 (00000000-00:00-00-00-00-00000000)" countryselected="7" countryname="VDE0126" idmodel="300001-3M60-3514" pout_kW="0.0477" pout_W="47.70" etot_Wh="7807852.25" etot_kWh="7807.852" co2_t="4.29" status="ALARM"  >"""

    const val PLANT = HEADER + """
 """ + CDD + """
   <powerlim enabl="0" ptotpv="1" plimval="100" status="0" /><edd sn="700001" pn="-3L03-" type="uInv" macrf="00:12:4B:00:00:00:01:01" wy="3413" x="1" y="1" fwmicro="C111" p1_ids="C231" fwdsp="B158" p1_idi="B202" fwrf="001" Etot_Wh="1939180.00" Etot_kWh="1939.180" Vout_V="236.44" Iout_A="0.038" Pout_W="8.22" Pout_kW="0.0082" Freq_Hz="49.96" Vin_V="38.90" Tdsp_degC="19.42" Tmos_degC="12.27" modeldesc="MICRO-0.3" invstat="RUN" booststat="RUN" alarmstat="0" rssi="31" rssizero="0" rssicnt="0" rssiavg="0" msgcnt="6" samples="5" ts="02-11-26 10:36:55" dspalarm="0x0000" mcualarm="0x0000" mcuwarning="0x0000" boosthexst="0x001D" invhexst="0x007F" avis="0x00000000" CS_crc="0x7F45" CS_status="OK" /><edd sn="700002" pn="-3L03-" type="uInv" macrf="00:12:4B:00:00:00:01:02" wy="3313" x="1" y="1" fwmicro="C111" p1_ids="C231" fwdsp="B158" p1_idi="B202" fwrf="001" Etot_Wh="3058140.25" Etot_kWh="3058.140" Vout_V="236.14" Iout_A="0.091" Pout_W="20.87" Pout_kW="0.0209" Freq_Hz="49.98" Vin_V="37.71" Tdsp_degC="20.77" Tmos_degC="14.42" modeldesc="MICRO-0.3" invstat="RUN" booststat="RUN" alarmstat="0" rssi="44" rssizero="0" rssicnt="11" rssiavg="44" msgcnt="6" samples="5" ts="02-11-26 10:36:56" dspalarm="0x0000" mcualarm="0x0000" mcuwarning="0x0000" boosthexst="0x001D" invhexst="0x007F" avis="0x00000000" CS_crc="0x7F45" CS_status="OK" /><edd sn="700003" pn="-3L03-" type="uInv" macrf="00:12:4B:00:00:00:01:03" wy="3313" x="1" y="1" fwmicro="C111" p1_ids="C231" fwdsp="B158" p1_idi="B202" fwrf="001" Etot_Wh="2810532.00" Etot_kWh="2810.532" Vout_V="237.53" Iout_A="0.083" Pout_W="18.61" Pout_kW="0.0186" Freq_Hz="49.95" Vin_V="37.48" Tdsp_degC="22.20" Tmos_degC="15.00" modeldesc="MICRO-0.3" invstat="RUN" booststat="RUN" alarmstat="0" rssi="39" rssizero="0" rssicnt="10" rssiavg="40" msgcnt="6" samples="5" ts="02-11-26 10:36:55" dspalarm="0x0000" mcualarm="0x0000" mcuwarning="0x0000" boosthexst="0x001D" invhexst="0x007F" avis="0x00000000" CS_crc="0x7F45" CS_status="OK" /><edd sn="700004" pn="-3L03-" type="uInv" macrf="00:12:4B:00:00:00:01:04" wy="3413" x="1" y="1" fwmicro="0000" p1_ids="0000" fwdsp="0000" p1_idi="0000" fwrf="000" Etot_Wh=" 0.00" Etot_kWh="0.000" Vout_V="0.00" Iout_A="0.000" Pout_W="0.00" Pout_kW="0.0000" Freq_Hz="0.00" Vin_V="0.00" Tdsp_degC="0.00" Tmos_degC="0.00" modeldesc="???" invstat="START-UP" booststat="START-UP" alarmstat="1" rssi="0" rssizero="0" rssicnt="0" rssiavg="0" msgcnt="0" samples="5" ts="01-01-00 00:00:00" dspalarm="0x0000" mcualarm="0x8000" mcuwarning="0x0000" boosthexst="0x001C" invhexst="0x0018" avis="0x80000000" CS_crc="0x0000" CS_status="Waiting" /><edd sn="xxxxxx" pn="xxxxxx" type="uInv" macrf="02:96:04:01:00:00:00:05" wy="xxxx" x="1" y="1" fwmicro="0000" p1_ids="0000" fwdsp="0000" p1_idi="0000" fwrf="000" Etot_Wh=" 0.00" Etot_kWh="0.000" Vout_V="0.00" Iout_A="0.000" Pout_W="0.00" Pout_kW="0.0000" Freq_Hz="0.00" Vin_V="0.00" Tdsp_degC="0.00" Tmos_degC="0.00" modeldesc="???" invstat="START-UP" booststat="START-UP" alarmstat="1" rssi="0" rssizero="0" rssicnt="0" rssiavg="0" msgcnt="0" samples="5" ts="01-01-00 00:00:00" dspalarm="0x0000" mcualarm="0x8000" mcuwarning="0x0000" boosthexst="0x001C" invhexst="0x0018" avis="0x80000000" CS_crc="0x0000" CS_status="Waiting" />
 </cdd>
</data>
"""

    const val SUMMARY = HEADER + "\n" + """<cdd macrf="00:12:4B:00:00:00:00:01" sn="300001" wakeup="10:39:13 16/09/26 (00000000-00:00-00-00-00-00000000)" countryselected="7" countryname="VDE0126" idmodel="300001-3M60-3514" pout_kW="0.0477" pout_W="47.70" etot_Wh="7807852.25" etot_kWh="7807.852" co2_t="4.29" status="ALARM"  />
</data>
"""

    /** The log as the CDD sends it: wake-up, the comm faults since then, and a closed cloud warning logged twice. */
    const val ALARMS = HEADER + """
 """ + CDD + """
 <alarm cnt="0-0" sn="300001" device="Cdd" code="I005" desc="CDD Wake Up 	   " time="16-09-26 10:39:13" len="99:59:59" status="Active" /><alarm cnt="1-1" sn="700004" device="uInv" code="E501" desc="Comm Fault      " time="16-09-26 10:59:18" len="99:59:59" status="Active" /><alarm cnt="1-2" sn="xxxxxx" device="uInv" code="E501" desc="Comm Fault      " time="16-09-26 10:59:18" len="99:59:59" status="Active" /><alarm cnt="2-3" sn="300001" device="Cdd" code="W021" desc="Gateway connec  " time="18-10-26 19:53:51" len="00:00:23" status="Closed" /><alarm cnt="2-4" sn="300001" device="Cdd" code="W021" desc="Gateway connec  " time="18-10-26 19:53:51" len="00:00:23" status="Closed" />
 </cdd>
</data>
"""

    /** The glitch seen about once in six reads: the header, but no `<alarm>` entries. */
    const val ALARMS_EMPTY = HEADER + "\n " + CDD + "\n </cdd>\n</data>\n"

    /** [PLANT] at night: nothing produced, `sun` no longer "DAY" (UNVERIFIED value; the real one wasn't seen yet). */
    val PLANT_NIGHT = PLANT.replace("sun=\"DAY\"", "sun=\"NIGHT\"").replace("pout_W=\"47.70\"", "pout_W=\"0.00\"")
}
