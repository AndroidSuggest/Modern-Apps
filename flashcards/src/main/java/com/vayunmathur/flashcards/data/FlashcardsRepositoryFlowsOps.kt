package com.vayunmathur.flashcards.data

import kotlinx.coroutines.flow.Flow

// ------------------------------------------------------------------
// Read flows (cold) + Deck + NoteType — moved from FlashcardsRepository.kt
// (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal fun FlashcardsRepository.decksFlow(): Flow<List<Deck>> = deckDao.getAllFlow()
internal fun FlashcardsRepository.cardsFlow(): Flow<List<Card>> = cardDao.getAllFlow()
internal fun FlashcardsRepository.notesFlow(): Flow<List<Note>> = noteDao.getAllFlow()
internal fun FlashcardsRepository.noteTypesFlow(): Flow<List<NoteType>> = noteTypeDao.getAllFlow()
internal fun FlashcardsRepository.noteTypeFieldsFlow(): Flow<List<NoteTypeField>> =
    noteTypeFieldDao.getAllFlow()
internal fun FlashcardsRepository.cardTemplatesFlow(): Flow<List<CardTemplate>> =
    cardTemplateDao.getAllFlow()
internal fun FlashcardsRepository.reviewLogsFlow(): Flow<List<ReviewLog>> = reviewLogDao.getAllFlow()

internal fun FlashcardsRepository.notesForImpl(deckId: Long): Flow<List<Note>> =
    noteDao.getByDeckFlow(deckId)
internal fun FlashcardsRepository.noteByIdImpl(id: Long): Flow<Note?> = noteDao.getByIdFlow(id)
internal fun FlashcardsRepository.cardsForImpl(deckId: Long): Flow<List<Card>> =
    cardDao.getByDeckFlow(deckId)
internal fun FlashcardsRepository.cardByIdImpl(id: Long): Flow<Card?> = cardDao.getByIdFlow(id)
internal fun FlashcardsRepository.dueCardsForImpl(deckId: Long, now: Long): Flow<List<Card>> =
    cardDao.getDueByDeckFlow(deckId, now)
internal fun FlashcardsRepository.reviewLogsForImpl(deckId: Long?): Flow<List<ReviewLog>> =
    if (deckId == null) reviewLogDao.getAllFlow() else reviewLogDao.getByDeckFlow(deckId)

internal suspend fun FlashcardsRepository.getAllDecksImpl(): List<Deck> = deckDao.getAll()
internal suspend fun FlashcardsRepository.getDeckImpl(id: Long): Deck? = deckDao.getById(id)
internal suspend fun FlashcardsRepository.upsertDeckImpl(deck: Deck): Long = deckDao.upsert(deck)
internal suspend fun FlashcardsRepository.deleteDeckImpl(deck: Deck): Int = deckDao.delete(deck)

internal suspend fun FlashcardsRepository.getAllNoteTypesImpl(): List<NoteType> = noteTypeDao.getAll()
internal suspend fun FlashcardsRepository.getNoteTypeImpl(id: Long): NoteType? = noteTypeDao.getById(id)
internal suspend fun FlashcardsRepository.upsertNoteTypeImpl(value: NoteType): Long =
    noteTypeDao.upsert(value)
internal suspend fun FlashcardsRepository.deleteNoteTypeImpl(value: NoteType): Int =
    noteTypeDao.delete(value)
