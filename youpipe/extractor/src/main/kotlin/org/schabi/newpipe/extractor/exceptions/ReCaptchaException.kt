package org.schabi.newpipe.extractor.exceptions

class ReCaptchaException(message: String, val url: String) : ExtractionException(message)
