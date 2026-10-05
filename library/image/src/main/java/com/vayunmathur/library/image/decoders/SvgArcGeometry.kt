package com.vayunmathur.library.image.decoders

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal fun arcTo(
    path: Path,
    x0: Float,
    y0: Float,
    rxIn: Float,
    ryIn: Float,
    angle: Float,
    largeArcFlag: Int,
    sweepFlag: Int,
    x1: Float,
    y1: Float,
) {
    if (rxIn == NO_ARC_RADIUS || ryIn == NO_ARC_RADIUS) {
        path.lineTo(x1, y1)
        return
    }
    val radii = correctedArcRadii(x0, y0, x1, y1, rxIn, ryIn, angle)
    val geometry = arcGeometry(x0, y0, x1, y1, radii, angle, largeArcFlag, sweepFlag)
    appendArcSegments(path, geometry, radii, angle)
}

internal const val NO_ARC_RADIUS = 0f

internal data class ArcRadii(val x: Double, val y: Double)

internal fun correctedArcRadii(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    rxIn: Float,
    ryIn: Float,
    angle: Float,
): ArcRadii {
    var radiusX = abs(rxIn).toDouble()
    var radiusY = abs(ryIn).toDouble()
    val midpoint = arcMidpointPrime(x0, y0, x1, y1, angle)
    val lambda = midpoint.x * midpoint.x / (radiusX * radiusX) +
        midpoint.y * midpoint.y / (radiusY * radiusY)
    if (lambda > ARC_LAMBDA_LIMIT) {
        val scale = sqrt(lambda)
        radiusX *= scale
        radiusY *= scale
    }
    return ArcRadii(radiusX, radiusY)
}

internal const val ARC_LAMBDA_LIMIT = 1.0

internal data class ArcMidpointPrime(val x: Double, val y: Double)

internal fun arcMidpointPrime(x0: Float, y0: Float, x1: Float, y1: Float, angle: Float): ArcMidpointPrime {
    val rotation = toArcRadians(angle)
    val halfX = (x0 - x1) / ARC_MIDPOINT_DIVISOR
    val halfY = (y0 - y1) / ARC_MIDPOINT_DIVISOR
    return ArcMidpointPrime(
        cos(rotation) * halfX + sin(rotation) * halfY,
        -sin(rotation) * halfX + cos(rotation) * halfY,
    )
}

internal const val ARC_MIDPOINT_DIVISOR = 2.0

internal fun toArcRadians(angle: Float): Double = Math.toRadians(angle.toDouble())

internal data class ArcGeometry(
    val centerX: Double,
    val centerY: Double,
    val startAngle: Double,
    val sweepAngle: Double,
)

internal fun arcGeometry(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    radii: ArcRadii,
    angle: Float,
    largeArcFlag: Int,
    sweepFlag: Int,
): ArcGeometry {
    val rotation = toArcRadians(angle)
    val midpoint = arcMidpointPrime(x0, y0, x1, y1, angle)
    val factor = arcCenterFactor(midpoint, radii, largeArcFlag, sweepFlag)
    val centerPrimeX = factor * (radii.x * midpoint.y / radii.y)
    val centerPrimeY = factor * (-radii.y * midpoint.x / radii.x)
    val cosPhi = cos(rotation)
    val sinPhi = sin(rotation)
    val centerX = cosPhi * centerPrimeX - sinPhi * centerPrimeY + (x0 + x1) / ARC_MIDPOINT_DIVISOR
    val centerY = sinPhi * centerPrimeX + cosPhi * centerPrimeY + (y0 + y1) / ARC_MIDPOINT_DIVISOR
    val start = arcEndpointAngle(centerPrimeX, centerPrimeY, radii, rotation, x0, y0)
    val end = arcEndpointAngle(centerPrimeX, centerPrimeY, radii, rotation, x1, y1)
    val sweep = arcSweepAngle(start, end, sweepFlag)
    return ArcGeometry(centerX, centerY, start.first, sweep)
}

internal fun arcCenterFactor(
    midpoint: ArcMidpointPrime,
    radii: ArcRadii,
    largeArcFlag: Int,
    sweepFlag: Int,
): Double {
    val radiusXSquared = radii.x * radii.x
    val radiusYSquared = radii.y * radii.y
    val primeXSquared = midpoint.x * midpoint.x
    val primeYSquared = midpoint.y * midpoint.y
    val numerator = radiusXSquared * radiusYSquared -
        radiusXSquared * primeYSquared -
        radiusYSquared * primeXSquared
    val denominator = radiusXSquared * primeYSquared + radiusYSquared * primeXSquared
    if (denominator == ARC_ZERO) return ARC_ZERO
    val sign = if (largeArcFlag == sweepFlag) -ARC_ONE else ARC_ONE
    return sign * sqrt(numerator.coerceAtLeast(ARC_ZERO) / denominator)
}

internal const val ARC_ZERO = 0.0
internal const val ARC_ONE = 1.0

internal fun arcEndpointAngle(
    centerPrimeX: Double,
    centerPrimeY: Double,
    radii: ArcRadii,
    rotation: Double,
    x: Float,
    y: Float,
): Pair<Double, Double> {
    val prime = arcPointPrime(x, y, rotation)
    val unitX = (prime.first - centerPrimeX) / radii.x
    val unitY = (prime.second - centerPrimeY) / radii.y
    return unitX to unitY
}

internal fun arcPointPrime(x: Float, y: Float, rotation: Double): Pair<Double, Double> {
    val cosPhi = cos(rotation)
    val sinPhi = sin(rotation)
    return (cosPhi * x + sinPhi * y) to (-sinPhi * x + cosPhi * y)
}

internal fun arcSweepAngle(start: Pair<Double, Double>, end: Pair<Double, Double>, sweepFlag: Int): Double {
    val startAngle = atan2(start.second, start.first)
    var sweep = atan2(
        start.first * end.second - start.second * end.first,
        start.first * end.first + start.second * end.second,
    )
    if (sweepFlag == ARC_SWEEP_ZERO && sweep > ARC_ZERO) {
        sweep -= ARC_FULL_CIRCLE
    }
    if (sweepFlag == ARC_SWEEP_ONE && sweep < ARC_ZERO) {
        sweep += ARC_FULL_CIRCLE
    }
    return sweep
}

internal const val ARC_SWEEP_ZERO = 0
internal const val ARC_SWEEP_ONE = 1
internal const val ARC_FULL_CIRCLE = 2 * Math.PI

internal data class ArcSegmentParams(
    val geometry: ArcGeometry,
    val radii: ArcRadii,
    val rotation: Double,
    val segmentAngle: Double,
)

internal fun appendArcSegments(path: Path, geometry: ArcGeometry, radii: ArcRadii, angle: Float) {
    val rotation = toArcRadians(angle)
    val segmentCount = ceil(abs(geometry.sweepAngle) / ARC_QUARTER_CIRCLE)
        .toInt()
        .coerceAtLeast(MIN_ARC_SEGMENTS)
    val params = ArcSegmentParams(
        geometry = geometry,
        radii = radii,
        rotation = rotation,
        segmentAngle = geometry.sweepAngle / segmentCount,
    )
    var currentAngle = geometry.startAngle
    repeat(segmentCount) {
        val nextAngle = currentAngle + params.segmentAngle
        appendArcSegment(path, params, currentAngle, nextAngle)
        currentAngle = nextAngle
    }
}

internal const val ARC_QUARTER_CIRCLE = Math.PI / 2
internal const val MIN_ARC_SEGMENTS = 1

internal fun appendArcSegment(path: Path, params: ArcSegmentParams, fromAngle: Double, toAngle: Double) {
    val end = arcPointOnEllipse(params, toAngle)
    val start = arcPointOnEllipse(params, fromAngle)
    val control = arcSegmentControls(params, fromAngle, toAngle)
    path.cubicTo(
        control.first.first.toFloat(),
        control.first.second.toFloat(),
        control.second.first.toFloat(),
        control.second.second.toFloat(),
        end.first.toFloat(),
        end.second.toFloat(),
    )
}

internal fun arcPointOnEllipse(params: ArcSegmentParams, angle: Double): Pair<Double, Double> {
    val cosPhi = cos(params.rotation)
    val sinPhi = sin(params.rotation)
    val ellipseX = params.radii.x * cos(angle)
    val ellipseY = params.radii.y * sin(angle)
    return (
        params.geometry.centerX + ellipseX * cosPhi - ellipseY * sinPhi
        ) to (
        params.geometry.centerY + ellipseX * sinPhi + ellipseY * cosPhi
        )
}

internal fun arcSegmentControls(
    params: ArcSegmentParams,
    fromAngle: Double,
    toAngle: Double,
): Pair<Pair<Double, Double>, Pair<Double, Double>> {
    val start = arcPointOnEllipse(params, fromAngle)
    val end = arcPointOnEllipse(params, toAngle)
    val factor = ARC_BEZIER_FACTOR * tan(params.segmentAngle / ARC_BEZIER_DIVISOR)
    val startTangent = arcTangent(params, fromAngle)
    val endTangent = arcTangent(params, toAngle)
    val control1 = start.first + factor * startTangent.first to start.second + factor * startTangent.second
    val control2 = end.first - factor * endTangent.first to end.second - factor * endTangent.second
    return control1 to control2
}

internal const val ARC_BEZIER_FACTOR = 4.0 / 3.0
internal const val ARC_BEZIER_DIVISOR = 4.0

internal fun arcTangent(params: ArcSegmentParams, angle: Double): Pair<Double, Double> {
    val cosPhi = cos(params.rotation)
    val sinPhi = sin(params.rotation)
    val derivativeX = -params.radii.x * sin(angle)
    val derivativeY = params.radii.y * cos(angle)
    return (
        derivativeX * cosPhi - derivativeY * sinPhi
        ) to (
        derivativeX * sinPhi + derivativeY * cosPhi
        )
}
