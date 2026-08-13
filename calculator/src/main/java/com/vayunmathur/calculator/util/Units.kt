package com.vayunmathur.calculator.util

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow

/**
 * A dimensional-analysis layer that lets the expression engine carry units through a
 * calculation (e.g. `5m + 2ft`) and lets the Units tab convert between them.
 *
 * The model is coherent-SI at heart: every [Quantity] holds its magnitude in coherent base
 * units (metre, kilogram, second, kelvin, …), so addition just checks the [Dimension]s match
 * and multiplication just multiplies. Temperatures are the one affine exception and are
 * documented on [Quantity].
 */

/** The seven SI base dimensions, plus INFORMATION so bytes/bits are first-class. */
enum class BaseDim { LENGTH, MASS, TIME, CURRENT, TEMPERATURE, AMOUNT, LUMINOUS, INFORMATION }

/**
 * A product of base dimensions with integer exponents (so speed is `LENGTH·TIME⁻¹`). Zero
 * exponents are dropped, so structural [equals] doubles as dimensional compatibility.
 */
class Dimension private constructor(val exponents: Map<BaseDim, Int>) {

    val isDimensionless: Boolean get() = exponents.isEmpty()

    operator fun times(other: Dimension) = of(merge(exponents, other.exponents, 1))
    operator fun div(other: Dimension) = of(merge(exponents, other.exponents, -1))
    fun pow(n: Int) = of(exponents.mapValues { it.value * n })

    override fun equals(other: Any?) = other is Dimension && other.exponents == exponents
    override fun hashCode() = exponents.hashCode()
    override fun toString() =
        if (isDimensionless) "1" else exponents.entries.joinToString("·") { "${it.key}^${it.value}" }

    companion object {
        val NONE = Dimension(emptyMap())

        fun of(map: Map<BaseDim, Int>) = Dimension(map.filterValues { it != 0 })
        private fun base(d: BaseDim) = of(mapOf(d to 1))

        private fun merge(a: Map<BaseDim, Int>, b: Map<BaseDim, Int>, sign: Int): Map<BaseDim, Int> {
            val out = a.toMutableMap()
            for ((k, v) in b) out[k] = (out[k] ?: 0) + sign * v
            return out
        }

        // Base dimensions.
        val LENGTH = base(BaseDim.LENGTH)
        val MASS = base(BaseDim.MASS)
        val TIME = base(BaseDim.TIME)
        val CURRENT = base(BaseDim.CURRENT)
        val TEMPERATURE = base(BaseDim.TEMPERATURE)
        val AMOUNT = base(BaseDim.AMOUNT)
        val INFORMATION = base(BaseDim.INFORMATION)

        // Common derived dimensions.
        val AREA = LENGTH.pow(2)
        val VOLUME = LENGTH.pow(3)
        val SPEED = LENGTH / TIME
        val FREQUENCY = TIME.pow(-1)
        val FORCE = MASS * LENGTH / TIME.pow(2)
        val ENERGY = FORCE * LENGTH
        val POWER = ENERGY / TIME
        val PRESSURE = FORCE / AREA
        val CHARGE = CURRENT * TIME
        val VOLTAGE = POWER / CURRENT
        val RESISTANCE = VOLTAGE / CURRENT
        val CAPACITANCE = CHARGE / VOLTAGE
    }
}

/**
 * A magnitude with a [dimension], as produced by evaluating an expression node.
 *
 * [value] is the linear magnitude in coherent base units (m, kg, s, …). Temperature is affine
 * and gets special treatment: [value] is a **kelvin-sized delta** and [tempOffsetK] distinguishes
 * an *absolute* temperature (`tempOffsetK != null`, absolute kelvin = `value + tempOffsetK`) from
 * a *delta* (`tempOffsetK == null`).
 *
 * The user's rule for `+`/`-` chains of temperatures (example `200k - 5c → 195K`): the first
 * temperature is absolute; each one added/subtracted onto it contributes only its degree-size
 * (its [value]), so `5c` behaves like a 5 K offset rather than 278.15 K.
 */
class Quantity(
    val value: Double,
    val dimension: Dimension,
    val tempOffsetK: Double? = null,
) {
    val isDimensionless: Boolean get() = dimension.isDimensionless
    private val isTemperature: Boolean get() = dimension == Dimension.TEMPERATURE

    operator fun unaryMinus() = Quantity(-value, dimension, tempOffsetK)

    operator fun plus(other: Quantity) = addOrSub(other, 1.0)
    operator fun minus(other: Quantity) = addOrSub(other, -1.0)

    private fun addOrSub(other: Quantity, sign: Double): Quantity {
        if (dimension != other.dimension) {
            throw ExpressionError("Cannot add or subtract incompatible units")
        }
        if (isTemperature) {
            // The right operand always collapses to its degree-size (a delta).
            val rightDelta = other.value
            return if (tempOffsetK != null) {
                // Absolute left: fold into absolute kelvin, then re-normalise (offset 0).
                Quantity((value + tempOffsetK) + sign * rightDelta, Dimension.TEMPERATURE, 0.0)
            } else {
                Quantity(value + sign * rightDelta, Dimension.TEMPERATURE, null)
            }
        }
        return Quantity(value + sign * other.value, dimension, null)
    }

    operator fun times(other: Quantity): Quantity {
        // A dimensionless scalar scales the other operand and preserves its temperature offset,
        // which is what turns `20 * celsius` into an absolute 293.15 K.
        if (isDimensionless && tempOffsetK == null) {
            return Quantity(value * other.value, other.dimension, other.tempOffsetK)
        }
        if (other.isDimensionless && other.tempOffsetK == null) {
            return Quantity(value * other.value, dimension, tempOffsetK)
        }
        // Two dimensional operands: combine dimensions; any temperature collapses to a delta.
        return Quantity(value * other.value, dimension * other.dimension, null)
    }

    operator fun div(other: Quantity): Quantity {
        if (other.isDimensionless && other.tempOffsetK == null) {
            return Quantity(value / other.value, dimension, tempOffsetK)
        }
        return Quantity(value / other.value, dimension / other.dimension, null)
    }

    operator fun rem(other: Quantity): Quantity {
        if (!other.isDimensionless && dimension != other.dimension) {
            throw ExpressionError("Cannot take the remainder of incompatible units")
        }
        return Quantity(value % other.value, dimension, null)
    }

    fun pow(other: Quantity): Quantity {
        if (!other.isDimensionless) throw ExpressionError("Exponent must be dimensionless")
        if (isDimensionless) return Quantity(value.pow(other.value), Dimension.NONE)
        val n = other.value
        if (n != floor(n)) throw ExpressionError("Cannot raise a unit to a non-integer power")
        return Quantity(value.pow(n), dimension.pow(n.toInt()))
    }

    fun abs(): Quantity = Quantity(kotlin.math.abs(value), dimension, tempOffsetK)

    companion object {
        fun scalar(value: Double) = Quantity(value, Dimension.NONE)
    }
}

/**
 * One unit the user can pick. [token] is the ASCII spelling the parser understands (inserted
 * by the keypad picker, e.g. `degC`, `um`, `ohm`); [symbol] is the pretty display form
 * (`°C`, `µm`, `Ω`); [aliases] are extra spellings the parser also accepts (e.g. `c` for `degC`).
 *
 * [factorToBase] scales a magnitude to coherent base units. [offsetK] is set only for affine
 * temperature units and is the absolute kelvin at zero of the unit (0 for K, 273.15 for °C).
 */
class UnitDef(
    val token: String,
    val symbol: String,
    val name: String,
    val dimension: Dimension,
    val factorToBase: Double,
    val offsetK: Double? = null,
    val aliases: List<String> = emptyList(),
) {
    /** Magnitude in this unit → coherent base value (absolute kelvin for temperatures). */
    fun toBase(magnitude: Double): Double = magnitude * factorToBase + (offsetK ?: 0.0)

    /** Coherent base value → magnitude in this unit. */
    fun fromBase(baseValue: Double): Double = (baseValue - (offsetK ?: 0.0)) / factorToBase
}

/** A named group of units, shown as one tab in the converter. */
class UnitCategory(
    val name: String,
    val units: List<UnitDef>,
    /** Whether these units are also usable inside equations (angle units are converter-only). */
    val inEquations: Boolean = true,
)

/**
 * The single source of unit truth: the converter's [categories], the parser's [parseTokens],
 * and the output-selector's [unitsFor].
 */
object UnitRegistry {

    // Names the engine reserves for variables/constants/ans; a unit token must never shadow one.
    private val RESERVED = setOf("e", "t", "x", "theta", "pi", "tau", "phi", "ans")

    private val FAHRENHEIT_OFFSET_K = 273.15 - 32.0 * 5.0 / 9.0

    val categories: List<UnitCategory> = buildCategories()

    /** Case-sensitive lookup for the parser, keyed by every token and alias. */
    val parseTokens: Map<String, UnitDef> = buildMap {
        for (category in categories) {
            if (!category.inEquations) continue
            for (unit in category.units) {
                for (key in listOf(unit.token) + unit.aliases) {
                    if (key.lowercase() in RESERVED) continue
                    putIfAbsent(key, unit)
                }
            }
        }
    }

    /** Units sharing [dimension], to populate the output-unit selector. */
    fun unitsFor(dimension: Dimension): List<UnitDef> =
        categories.flatMap { it.units }.filter { it.dimension == dimension }

    /** A sensible default output unit for [dimension]: the coherent base if present. */
    fun defaultUnitFor(dimension: Dimension): UnitDef? {
        val options = unitsFor(dimension)
        return options.firstOrNull { it.factorToBase == 1.0 && (it.offsetK == null || it.offsetK == 0.0) }
            ?: options.firstOrNull()
    }

    private fun buildCategories(): List<UnitCategory> {
        val d = Dimension
        return listOf(
            UnitCategory(
                "Length",
                listOf(
                    UnitDef("km", "km", "Kilometre", d.LENGTH, 1000.0),
                    UnitDef("m", "m", "Metre", d.LENGTH, 1.0),
                    UnitDef("cm", "cm", "Centimetre", d.LENGTH, 0.01),
                    UnitDef("mm", "mm", "Millimetre", d.LENGTH, 0.001),
                    UnitDef("um", "µm", "Micrometre", d.LENGTH, 1e-6),
                    UnitDef("nm", "nm", "Nanometre", d.LENGTH, 1e-9),
                    UnitDef("mi", "mi", "Mile", d.LENGTH, 1609.344),
                    UnitDef("yd", "yd", "Yard", d.LENGTH, 0.9144),
                    UnitDef("ft", "ft", "Foot", d.LENGTH, 0.3048),
                    UnitDef("in", "in", "Inch", d.LENGTH, 0.0254),
                    UnitDef("nmi", "nmi", "Nautical mile", d.LENGTH, 1852.0),
                ),
            ),
            UnitCategory(
                "Mass",
                listOf(
                    UnitDef("tonne", "t", "Tonne", d.MASS, 1000.0),
                    UnitDef("kg", "kg", "Kilogram", d.MASS, 1.0),
                    UnitDef("g", "g", "Gram", d.MASS, 0.001),
                    UnitDef("mg", "mg", "Milligram", d.MASS, 1e-6),
                    UnitDef("ug", "µg", "Microgram", d.MASS, 1e-9),
                    UnitDef("lb", "lb", "Pound", d.MASS, 0.45359237),
                    UnitDef("oz", "oz", "Ounce", d.MASS, 0.028349523125),
                    UnitDef("st", "st", "Stone", d.MASS, 6.35029318),
                ),
            ),
            UnitCategory(
                "Time",
                listOf(
                    UnitDef("ns", "ns", "Nanosecond", d.TIME, 1e-9),
                    UnitDef("us", "µs", "Microsecond", d.TIME, 1e-6),
                    UnitDef("ms", "ms", "Millisecond", d.TIME, 0.001),
                    UnitDef("s", "s", "Second", d.TIME, 1.0),
                    UnitDef("min", "min", "Minute", d.TIME, 60.0),
                    UnitDef("h", "h", "Hour", d.TIME, 3600.0),
                    UnitDef("day", "day", "Day", d.TIME, 86400.0),
                    UnitDef("wk", "wk", "Week", d.TIME, 604800.0),
                    UnitDef("yr", "yr", "Year", d.TIME, 31557600.0),
                ),
            ),
            UnitCategory(
                "Temperature",
                listOf(
                    UnitDef("K", "K", "Kelvin", d.TEMPERATURE, 1.0, offsetK = 0.0, aliases = listOf("k")),
                    UnitDef("degC", "°C", "Celsius", d.TEMPERATURE, 1.0, offsetK = 273.15, aliases = listOf("c")),
                    UnitDef("degF", "°F", "Fahrenheit", d.TEMPERATURE, 5.0 / 9.0, offsetK = FAHRENHEIT_OFFSET_K),
                    UnitDef("degR", "°R", "Rankine", d.TEMPERATURE, 5.0 / 9.0, offsetK = 0.0),
                ),
            ),
            UnitCategory(
                "Area",
                listOf(
                    UnitDef("km2", "km²", "Square kilometre", d.AREA, 1e6),
                    UnitDef("m2", "m²", "Square metre", d.AREA, 1.0),
                    UnitDef("cm2", "cm²", "Square centimetre", d.AREA, 1e-4),
                    UnitDef("mm2", "mm²", "Square millimetre", d.AREA, 1e-6),
                    UnitDef("ha", "ha", "Hectare", d.AREA, 10000.0),
                    UnitDef("acre", "acre", "Acre", d.AREA, 4046.8564224),
                    UnitDef("ft2", "ft²", "Square foot", d.AREA, 0.09290304),
                    UnitDef("in2", "in²", "Square inch", d.AREA, 0.00064516),
                    UnitDef("mi2", "mi²", "Square mile", d.AREA, 2589988.110336),
                ),
            ),
            UnitCategory(
                "Volume",
                listOf(
                    UnitDef("m3", "m³", "Cubic metre", d.VOLUME, 1.0),
                    UnitDef("cm3", "cm³", "Cubic centimetre", d.VOLUME, 1e-6),
                    UnitDef("L", "L", "Litre", d.VOLUME, 0.001, aliases = listOf("l")),
                    UnitDef("mL", "mL", "Millilitre", d.VOLUME, 1e-6, aliases = listOf("ml")),
                    UnitDef("gal", "gal", "Gallon (US)", d.VOLUME, 0.003785411784),
                    UnitDef("qt", "qt", "Quart (US)", d.VOLUME, 9.46352946e-4),
                    UnitDef("pt", "pt", "Pint (US)", d.VOLUME, 4.73176473e-4),
                    UnitDef("cup", "cup", "Cup (US)", d.VOLUME, 2.365882365e-4),
                    UnitDef("floz", "fl oz", "Fluid ounce (US)", d.VOLUME, 2.95735295625e-5),
                    UnitDef("ft3", "ft³", "Cubic foot", d.VOLUME, 0.028316846592),
                    UnitDef("in3", "in³", "Cubic inch", d.VOLUME, 1.6387064e-5),
                ),
            ),
            UnitCategory(
                "Speed",
                listOf(
                    UnitDef("mps", "m/s", "Metres per second", d.SPEED, 1.0),
                    UnitDef("kmh", "km/h", "Kilometres per hour", d.SPEED, 1.0 / 3.6),
                    UnitDef("mph", "mph", "Miles per hour", d.SPEED, 0.44704),
                    UnitDef("fps", "ft/s", "Feet per second", d.SPEED, 0.3048),
                    UnitDef("kn", "kn", "Knot", d.SPEED, 0.514444),
                ),
            ),
            UnitCategory(
                "Data",
                listOf(
                    UnitDef("bit", "bit", "Bit", d.INFORMATION, 1.0),
                    UnitDef("B", "B", "Byte", d.INFORMATION, 8.0),
                    UnitDef("kB", "kB", "Kilobyte", d.INFORMATION, 8e3),
                    UnitDef("MB", "MB", "Megabyte", d.INFORMATION, 8e6),
                    UnitDef("GB", "GB", "Gigabyte", d.INFORMATION, 8e9),
                    UnitDef("TB", "TB", "Terabyte", d.INFORMATION, 8e12),
                    UnitDef("KiB", "KiB", "Kibibyte", d.INFORMATION, 8.0 * 1024),
                    UnitDef("MiB", "MiB", "Mebibyte", d.INFORMATION, 8.0 * 1024 * 1024),
                    UnitDef("GiB", "GiB", "Gibibyte", d.INFORMATION, 8.0 * 1024 * 1024 * 1024),
                    UnitDef("TiB", "TiB", "Tebibyte", d.INFORMATION, 8.0 * 1024 * 1024 * 1024 * 1024),
                    UnitDef("kbit", "kbit", "Kilobit", d.INFORMATION, 1e3),
                    UnitDef("Mbit", "Mbit", "Megabit", d.INFORMATION, 1e6),
                    UnitDef("Gbit", "Gbit", "Gigabit", d.INFORMATION, 1e9),
                ),
            ),
            UnitCategory(
                "Energy",
                listOf(
                    UnitDef("J", "J", "Joule", d.ENERGY, 1.0),
                    UnitDef("kJ", "kJ", "Kilojoule", d.ENERGY, 1000.0),
                    UnitDef("MJ", "MJ", "Megajoule", d.ENERGY, 1e6),
                    UnitDef("cal", "cal", "Calorie", d.ENERGY, 4.184),
                    UnitDef("kcal", "kcal", "Kilocalorie", d.ENERGY, 4184.0),
                    UnitDef("Wh", "Wh", "Watt-hour", d.ENERGY, 3600.0),
                    UnitDef("kWh", "kWh", "Kilowatt-hour", d.ENERGY, 3.6e6),
                    UnitDef("eV", "eV", "Electronvolt", d.ENERGY, 1.602176634e-19),
                    UnitDef("BTU", "BTU", "British thermal unit", d.ENERGY, 1055.05585262),
                    UnitDef("erg", "erg", "Erg", d.ENERGY, 1e-7),
                ),
            ),
            UnitCategory(
                "Power",
                listOf(
                    UnitDef("mW", "mW", "Milliwatt", d.POWER, 0.001),
                    UnitDef("W", "W", "Watt", d.POWER, 1.0),
                    UnitDef("kW", "kW", "Kilowatt", d.POWER, 1000.0),
                    UnitDef("MW", "MW", "Megawatt", d.POWER, 1e6),
                    UnitDef("GW", "GW", "Gigawatt", d.POWER, 1e9),
                    UnitDef("hp", "hp", "Horsepower", d.POWER, 745.6998715823),
                ),
            ),
            UnitCategory(
                "Pressure",
                listOf(
                    UnitDef("Pa", "Pa", "Pascal", d.PRESSURE, 1.0),
                    UnitDef("hPa", "hPa", "Hectopascal", d.PRESSURE, 100.0),
                    UnitDef("kPa", "kPa", "Kilopascal", d.PRESSURE, 1000.0),
                    UnitDef("MPa", "MPa", "Megapascal", d.PRESSURE, 1e6),
                    UnitDef("bar", "bar", "Bar", d.PRESSURE, 1e5),
                    UnitDef("mbar", "mbar", "Millibar", d.PRESSURE, 100.0),
                    UnitDef("atm", "atm", "Atmosphere", d.PRESSURE, 101325.0),
                    UnitDef("psi", "psi", "Pound per square inch", d.PRESSURE, 6894.757293168),
                    UnitDef("mmHg", "mmHg", "Millimetre of mercury", d.PRESSURE, 133.322387415),
                    UnitDef("torr", "torr", "Torr", d.PRESSURE, 133.32236842105263),
                ),
            ),
            UnitCategory(
                "Force",
                listOf(
                    UnitDef("N", "N", "Newton", d.FORCE, 1.0),
                    UnitDef("kN", "kN", "Kilonewton", d.FORCE, 1000.0),
                    UnitDef("mN", "mN", "Millinewton", d.FORCE, 0.001),
                    UnitDef("lbf", "lbf", "Pound-force", d.FORCE, 4.4482216152605),
                    UnitDef("kgf", "kgf", "Kilogram-force", d.FORCE, 9.80665),
                    UnitDef("dyn", "dyn", "Dyne", d.FORCE, 1e-5),
                ),
            ),
            UnitCategory(
                "Frequency",
                listOf(
                    UnitDef("Hz", "Hz", "Hertz", d.FREQUENCY, 1.0),
                    UnitDef("kHz", "kHz", "Kilohertz", d.FREQUENCY, 1000.0),
                    UnitDef("MHz", "MHz", "Megahertz", d.FREQUENCY, 1e6),
                    UnitDef("GHz", "GHz", "Gigahertz", d.FREQUENCY, 1e9),
                ),
            ),
            UnitCategory(
                "Current",
                listOf(
                    UnitDef("uA", "µA", "Microampere", d.CURRENT, 1e-6),
                    UnitDef("mA", "mA", "Milliampere", d.CURRENT, 0.001),
                    UnitDef("A", "A", "Ampere", d.CURRENT, 1.0),
                    UnitDef("kA", "kA", "Kiloampere", d.CURRENT, 1000.0),
                ),
            ),
            UnitCategory(
                "Voltage",
                listOf(
                    UnitDef("uV", "µV", "Microvolt", d.VOLTAGE, 1e-6),
                    UnitDef("mV", "mV", "Millivolt", d.VOLTAGE, 0.001),
                    UnitDef("V", "V", "Volt", d.VOLTAGE, 1.0),
                    UnitDef("kV", "kV", "Kilovolt", d.VOLTAGE, 1000.0),
                ),
            ),
            UnitCategory(
                "Resistance",
                listOf(
                    UnitDef("mohm", "mΩ", "Milliohm", d.RESISTANCE, 0.001),
                    UnitDef("ohm", "Ω", "Ohm", d.RESISTANCE, 1.0),
                    UnitDef("kohm", "kΩ", "Kiloohm", d.RESISTANCE, 1000.0),
                    UnitDef("Mohm", "MΩ", "Megaohm", d.RESISTANCE, 1e6),
                ),
            ),
            UnitCategory(
                "Charge",
                listOf(
                    UnitDef("uC", "µC", "Microcoulomb", d.CHARGE, 1e-6),
                    UnitDef("mC", "mC", "Millicoulomb", d.CHARGE, 0.001),
                    UnitDef("C", "C", "Coulomb", d.CHARGE, 1.0),
                    UnitDef("mAh", "mAh", "Milliamp-hour", d.CHARGE, 3.6),
                    UnitDef("Ah", "Ah", "Amp-hour", d.CHARGE, 3600.0),
                ),
            ),
            UnitCategory(
                "Capacitance",
                listOf(
                    UnitDef("pF", "pF", "Picofarad", d.CAPACITANCE, 1e-12),
                    UnitDef("nF", "nF", "Nanofarad", d.CAPACITANCE, 1e-9),
                    UnitDef("uF", "µF", "Microfarad", d.CAPACITANCE, 1e-6),
                    UnitDef("mF", "mF", "Millifarad", d.CAPACITANCE, 0.001),
                    UnitDef("F", "F", "Farad", d.CAPACITANCE, 1.0),
                ),
            ),
            UnitCategory(
                "Amount",
                listOf(
                    UnitDef("umol", "µmol", "Micromole", d.AMOUNT, 1e-6),
                    UnitDef("mmol", "mmol", "Millimole", d.AMOUNT, 0.001),
                    UnitDef("mol", "mol", "Mole", d.AMOUNT, 1.0),
                    UnitDef("kmol", "kmol", "Kilomole", d.AMOUNT, 1000.0),
                ),
            ),
            UnitCategory(
                "Angle",
                listOf(
                    UnitDef("rad", "rad", "Radian", Dimension.NONE, 1.0),
                    UnitDef("deg", "°", "Degree", Dimension.NONE, PI / 180.0),
                    UnitDef("grad", "grad", "Gradian", Dimension.NONE, PI / 200.0),
                    UnitDef("arcmin", "′", "Arcminute", Dimension.NONE, PI / 10800.0),
                    UnitDef("arcsec", "″", "Arcsecond", Dimension.NONE, PI / 648000.0),
                    UnitDef("rev", "rev", "Revolution", Dimension.NONE, 2 * PI),
                ),
                inEquations = false,
            ),
        )
    }
}
