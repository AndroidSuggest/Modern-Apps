package com.vayunmathur.flashcards.data

// ------------------------------------------------------------------
// NoteTypeField + CardTemplate — moved from FlashcardsRepository.kt
// (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal suspend fun FlashcardsRepository.getAllNoteTypeFieldsImpl(): List<NoteTypeField> =
    noteTypeFieldDao.getAll()
internal suspend fun FlashcardsRepository.getFieldsForNoteTypeImpl(noteTypeId: Long): List<NoteTypeField> =
    noteTypeFieldDao.getByNoteType(noteTypeId)
internal suspend fun FlashcardsRepository.upsertNoteTypeFieldImpl(value: NoteTypeField): Long =
    noteTypeFieldDao.upsert(value)
internal suspend fun FlashcardsRepository.upsertNoteTypeFieldsImpl(values: List<NoteTypeField>) =
    noteTypeFieldDao.upsertAll(values)
internal suspend fun FlashcardsRepository.deleteFieldsByNoteTypeImpl(noteTypeId: Long) =
    noteTypeFieldDao.deleteByNoteType(noteTypeId)

internal suspend fun FlashcardsRepository.getAllCardTemplatesImpl(): List<CardTemplate> =
    cardTemplateDao.getAll()
internal suspend fun FlashcardsRepository.getTemplatesForNoteTypeImpl(noteTypeId: Long): List<CardTemplate> =
    cardTemplateDao.getByNoteType(noteTypeId)
internal suspend fun FlashcardsRepository.upsertCardTemplateImpl(value: CardTemplate): Long =
    cardTemplateDao.upsert(value)
internal suspend fun FlashcardsRepository.upsertCardTemplatesImpl(values: List<CardTemplate>) =
    cardTemplateDao.upsertAll(values)
internal suspend fun FlashcardsRepository.deleteTemplatesByNoteTypeImpl(noteTypeId: Long) =
    cardTemplateDao.deleteByNoteType(noteTypeId)
