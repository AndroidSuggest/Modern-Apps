package org.schabi.newpipe.extractor.utils

import java.io.Serializable
import java.util.Objects

/**
 * Serializable class to create a pair of objects.
 *
 * The two objects of the pair must be [Serializable] and can be of the same type.
 *
 * Note that this class is not intended to be used as a general-purpose pair and should only be
 * used when interfacing with the extractor.
 *
 * @param F the type of the first object, which must be [Serializable]
 * @param S the type of the second object, which must be [Serializable]
 */
class Pair<F : Serializable, S : Serializable> : Serializable {

    /**
     * The first object of the pair.
     */
    private var firstObject: F

    /**
     * The second object of the pair.
     */
    private var secondObject: S

    /**
     * Creates a new [Pair] object.
     *
     * @param first  the first object of the pair
     * @param second the second object of the pair
     */
    constructor(first: F, second: S) {
        firstObject = first
        secondObject = second
    }

    /**
     * Sets the first object, which must be of the [F] type.
     *
     * @param first the new first object of the pair
     */
    fun setFirst(first: F) {
        firstObject = first
    }

    /**
     * Sets the second object, which must be of the [S] type.
     *
     * @param second the new second object of the pair
     */
    fun setSecond(second: S) {
        secondObject = second
    }

    /**
     * Gets the first object of the pair.
     *
     * @return the first object of the pair
     */
    fun getFirst(): F = firstObject

    /**
     * Gets the second object of the pair.
     *
     * @return the second object of the pair
     */
    fun getSecond(): S = secondObject

    override fun toString(): String = "{$firstObject, $secondObject}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val pair = other as Pair<*, *>
        return firstObject == pair.firstObject &&
            secondObject == pair.secondObject
    }

    override fun hashCode(): Int = Objects.hash(firstObject, secondObject)
}
