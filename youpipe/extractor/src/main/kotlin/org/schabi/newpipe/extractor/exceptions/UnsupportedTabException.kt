package org.schabi.newpipe.extractor.exceptions

class UnsupportedTabException(unsupportedTab: String) :
    UnsupportedOperationException("Unsupported tab $unsupportedTab")
