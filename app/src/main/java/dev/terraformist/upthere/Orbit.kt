package dev.terraformist.upthere

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ISS's orbit as published (a "two-line element set", from CelesTrak), and SGP4: the standard
 * way to turn one into a position at any moment. No Android code here, so it can be checked on a
 * computer against a reference library.
 */
class Tle private constructor(val line1: String, val line2: String) {

    /** NORAD catalogue number (the ISS is 25544). */
    val catalog: Int = line1.substring(2, 7).trim().toInt()

    /** When this orbit was measured, in milliseconds since 1970 (UTC). */
    val epochMillis: Long

    val bstar: Double
    val inclination: Double // radians
    val raan: Double // radians
    val eccentricity: Double
    val argPerigee: Double // radians
    val meanAnomaly: Double // radians
    val meanMotion: Double // radians per minute

    init {
        val yy = line1.substring(18, 20).trim().toInt()
        val year = if (yy < 57) 2000 + yy else 1900 + yy
        val day = line1.substring(20, 32).trim().toDouble()
        val jan1 = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        epochMillis = jan1 + ((day - 1.0) * DAY_MS).toLong()
        bstar = exponent(line1.substring(53, 61))

        val deg = PI / 180.0
        inclination = line2.substring(8, 16).trim().toDouble() * deg
        raan = line2.substring(17, 25).trim().toDouble() * deg
        eccentricity = ("0." + line2.substring(26, 33).trim()).toDouble()
        argPerigee = line2.substring(34, 42).trim().toDouble() * deg
        meanAnomaly = line2.substring(43, 51).trim().toDouble() * deg
        meanMotion = line2.substring(52, 63).trim().toDouble() * 2.0 * PI / 1440.0
    }

    /** Both lines, ready to save. */
    val text: String get() = "$line1\n$line2"

    companion object {
        const val ISS = 25544
        private const val DAY_MS = 86_400_000.0

        /** Finds the first valid element set in [text] (with or without a name line), or null. */
        fun parse(text: String): Tle? {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            for (i in 0 until lines.size - 1) {
                val a = lines[i]
                val b = lines[i + 1]
                if (!a.startsWith("1 ") || !b.startsWith("2 ")) continue
                if (!checksumOk(a) || !checksumOk(b)) continue
                return try {
                    Tle(a, b)
                } catch (e: RuntimeException) {
                    null
                }
            }
            return null
        }

        /** The last digit of each line is a checksum: digits add up, minus signs count as 1. */
        fun checksumOk(line: String): Boolean {
            if (line.length < 69) return false
            var sum = 0
            for (ch in line.substring(0, 68)) {
                if (ch.isDigit()) sum += ch - '0' else if (ch == '-') sum += 1
            }
            val expected = line[68]
            return expected.isDigit() && sum % 10 == expected - '0'
        }

        /** "-11606-4" means -0.11606 × 10⁻⁴. */
        private fun exponent(field: String): Double {
            var s = field.trim()
            if (s.isEmpty()) return 0.0
            var sign = 1.0
            if (s[0] == '-' || s[0] == '+') {
                if (s[0] == '-') sign = -1.0
                s = s.substring(1)
            }
            val cut = maxOf(s.lastIndexOf('-'), s.lastIndexOf('+'))
            if (cut <= 0) return sign * ("0.$s").toDouble()
            val mantissa = ("0." + s.substring(0, cut)).toDouble()
            val exp = s.substring(cut).toInt()
            return sign * mantissa * 10.0.pow(exp)
        }
    }
}

/**
 * SGP4 for near-Earth orbits (anything going round in under 225 minutes, which the ISS does in
 * about 92). Follows Vallado's reference implementation (2006, revised), WGS-72 constants.
 * Positions are in kilometres, in the TEME frame (an Earth-centred frame that doesn't turn with the Earth).
 */
class Sgp4(val tle: Tle) {

    private val ecco = tle.eccentricity
    private val inclo = tle.inclination
    private val nodeo = tle.raan
    private val argpo = tle.argPerigee
    private val mo = tle.meanAnomaly
    private val bstar = tle.bstar

    private val no: Double
    private val isimp: Boolean
    private val con41: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val xmcof: Double
    private val mdot: Double
    private val nodedot: Double
    private val nodecf: Double
    private val xlcof: Double
    private val aycof: Double

    init {
        // ---- initl ----
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (XKE / tle.meanMotion).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        no = tle.meanMotion / (1.0 + del)
        val ao = (XKE / no).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        // ---- sgp4init ----
        val ss = 78.0 / RE + 1.0
        val qzms2t = ((120.0 - 78.0) / RE).pow(4)
        isimp = rp < (220.0 / RE + 1.0)
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (
            ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq))
            )
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * J3OJ2 * no * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * no * coef1 * ao * omeosq * (
            eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                J2 * tsi / (ao * psisq) * (
                    -3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)
                    )
            )
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        } else {
            -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12
        }
        aycof = -0.5 * J3OJ2 * sinio
        val delmotemp = 1.0 + eta * cos(mo)
        delmo = delmotemp * delmotemp * delmotemp
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0

        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    /** Position in km (TEME) at [millis] (UTC, ms since 1970). */
    fun position(millis: Long): DoubleArray = positionAt((millis - tle.epochMillis) / 60_000.0)

    /** Position in km (TEME), [t] minutes after the element set's epoch. */
    fun positionAt(t: Double): DoubleArray {
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (!isimp) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }

        var nm = no
        var em = ecco
        val am = (XKE / nm).pow(X2O3) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem = mod2pi(nodem)
        argpm = mod2pi(argpm)
        xlm = mod2pi(xlm)
        mm = mod2pi(xlm - argpm - nodem)

        val sinip = sin(inclo)
        val cosip = cos(inclo)

        // ---- long-period periodics ----
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        // ---- Kepler's equation ----
        val u = mod2pi(xl - nodem)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // ---- short-period periodics ----
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        val rl = am * (1.0 - ecose)
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u

        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu

        val r = mrt * RE
        return doubleArrayOf(r * ux, r * uy, r * uz)
    }

    private companion object {
        // WGS-72, as SGP4 expects.
        const val MU = 398600.8
        const val RE = 6378.135
        val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2
        const val X2O3 = 2.0 / 3.0

        fun mod2pi(x: Double): Double {
            val t = 2.0 * PI
            return x - t * floor(x / t)
        }
    }
}
