package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import javax.annotation.Nullable

abstract class InfoItemsCollector<I : InfoItem, E : InfoItemExtractor> : Collector<I, E> {

    private val itemList: MutableList<I> = ArrayList()
    private val errors: MutableList<Throwable> = ArrayList()
    private val serviceId: Int
    @Nullable
    private val comparator: Comparator<I>?

    constructor(serviceId: Int) : this(serviceId, null)

    constructor(serviceId: Int, @Nullable comparator: Comparator<I>?) {
        this.serviceId = serviceId
        this.comparator = comparator
    }

    override fun getItems(): List<I> {
        if (comparator != null) {
            itemList.sortWith(comparator)
        }
        return Collections.unmodifiableList(itemList)
    }

    override fun getErrors(): List<Throwable> = Collections.unmodifiableList(errors)

    override fun reset() {
        itemList.clear()
        errors.clear()
    }

    protected fun addError(error: Exception) {
        errors.add(error)
    }

    protected fun addItem(item: I) {
        itemList.add(item)
    }

    fun getServiceId(): Int = serviceId

    override fun commit(extractor: E) {
        try {
            addItem(extract(extractor))
        } catch (ae: FoundAdException) {
            // found an ad, ignore
        } catch (e: ParsingException) {
            addError(e)
        }
    }
}
