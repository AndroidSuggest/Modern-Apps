package com.vayunmathur.flashcards.data

// ------------------------------------------------------------------
// Note — moved from FlashcardsRepository.kt (TooManyFunctions split);
// behavior identical.
// ------------------------------------------------------------------

internal suspend fun FlashcardsRepository.getAllNotesImpl(): List<Note> = noteDao.getAll()
internal suspend fun FlashcardsRepository.getNotesByDeckImpl(deckId: Long): List<Note> =
    noteDao.getByDeck(deckId)
internal suspend fun FlashcardsRepository.getNotesByNoteTypeImpl(noteTypeId: Long): List<Note> =
    noteDao.getByNoteType(noteTypeId)
internal suspend fun FlashcardsRepository.getNoteImpl(id: Long): Note? = noteDao.getById(id)
internal suspend fun FlashcardsRepository.getNotesByIdsImpl(ids: List<Long>): List<Note> =
    noteDao.getByIds(ids)
internal suspend fun FlashcardsRepository.upsertNoteImpl(value: Note): Long = noteDao.upsert(value)
internal suspend fun FlashcardsRepository.upsertNotesImpl(values: List<Note>) = noteDao.upsertAll(values)
internal suspend fun FlashcardsRepository.deleteNoteImpl(value: Note): Int = noteDao.delete(value)
internal suspend fun FlashcardsRepository.deleteNotesByIdsImpl(ids: List<Long>) = noteDao.deleteByIds(ids)
internal suspend fun FlashcardsRepository.deleteNotesByDeckImpl(deckId: Long) = noteDao.deleteByDeck(deckId)
