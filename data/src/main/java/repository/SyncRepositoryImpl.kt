/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package repository

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import io.realm.Realm
import mapper.CursorToContact
import mapper.CursorToConversation
import mapper.CursorToMessage
import mapper.CursorToRecipient
import model.Contact
import model.Conversation
import model.Message
import model.MmsPart
import model.Recipient
import model.SyncLog
import util.extensions.insertOrUpdate
import util.extensions.map
import util.extensions.mapWhile
import util.tryOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
        private val contentResolver: ContentResolver,
        private val messageRepo: MessageRepository,
        private val cursorToConversation: CursorToConversation,
        private val cursorToMessage: CursorToMessage,
        private val cursorToRecipient: CursorToRecipient,
        private val cursorToContact: CursorToContact
) : SyncRepository {

    sealed class Status {
        class Idle : Status()
        class Running : Status()
    }

    /**
     * Holds data that should be persisted across full syncs
     */
    private data class PersistedData(val id: Long, val archived: Boolean, val blocked: Boolean)

    private var status: Status = Status.Idle()

    override fun syncMessages(fullSync: Boolean) {

        // If the sync is already running, don't try to do another one
        if (status is Status.Running) return
        status = Status.Running()

        val realm = Realm.getDefaultInstance()
        realm.beginTransaction()

        var persistedData: List<PersistedData>? = null

        if (fullSync) {
            persistedData = realm.where(Conversation::class.java)
                    .beginGroup()
                    .equalTo("archived", true)
                    .or()
                    .equalTo("blocked", true)
                    .endGroup()
                    .findAll()
                    .map { conversation ->
                        PersistedData(conversation.id, conversation.archived, conversation.blocked)
                    }

            realm.delete(Conversation::class.java)
            realm.delete(Message::class.java)
            realm.delete(MmsPart::class.java)
            realm.delete(Recipient::class.java)
            realm.delete(SyncLog::class.java)
        }

        val lastSync = realm.where(Message::class.java)?.max("date")?.toLong() ?: 0
        realm.insert(SyncLog())


        // Sync messages
        cursorToMessage.getMessagesCursor()?.use { messageCursor ->
            val messageColumns = CursorToMessage.MessageColumns(messageCursor)
            val messages = messageCursor.mapWhile(
                    { cursor -> cursorToMessage.map(Pair(cursor, messageColumns)) },
                    { message -> message.date > lastSync })
            realm.insertOrUpdate(messages)
        }


        // Sync conversations
        cursorToConversation.getConversationsCursor()?.use { conversationCursor ->
            val conversations = conversationCursor
                    .map { cursor -> cursorToConversation.map(cursor) }

            persistedData?.forEach { data ->
                val conversation = conversations.firstOrNull { conversation -> conversation.id == data.id }
                conversation?.archived = data.archived
                conversation?.blocked = data.blocked
            }

            realm.insertOrUpdate(conversations)
        }


        // Sync recipients
        cursorToRecipient.getRecipientCursor()?.use { recipientCursor ->
            val recipients = recipientCursor.map { cursor -> cursorToRecipient.map(cursor) }
            realm.insertOrUpdate(recipients)
        }

        realm.commitTransaction()
        realm.close()

        syncContacts()

        status = Status.Idle()
    }

    override fun syncMessage(uri: Uri): Message? {

        // If we don't have a valid type, return null
        val type = when {
            uri.toString().contains("mms") -> "mms"
            uri.toString().contains("sms") -> "sms"
            else -> return null
        }

        // If we don't have a valid id, return null
        val id = tryOrNull { ContentUris.parseId(uri) } ?: return null

        // Check if the message already exists, so we can reuse the id
        val existingId = Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Message::class.java)
                    .equalTo("type", type)
                    .equalTo("contentId", id)
                    .findFirst()
                    ?.id
        }

        // The uri might be something like content://mms/inbox/id
        // The box might change though, so we should just use the mms/id uri
        val stableUri = when (type) {
            "mms" -> ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            else -> ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        return contentResolver.query(stableUri, null, null, null, null)?.use { cursor ->

            // If there are no rows, return null. Otherwise, we've moved to the first row
            if (!cursor.moveToFirst()) return null

            val columnsMap = CursorToMessage.MessageColumns(cursor)
            cursorToMessage.map(Pair(cursor, columnsMap)).apply {
                existingId?.let { this.id = it }

                messageRepo.getOrCreateConversation(threadId)
                insertOrUpdate()
            }
        }
    }

    override fun syncContacts() {
        // Load all the contacts
        var contacts = cursorToContact.getContactsCursor()
                ?.map { cursor -> cursorToContact.map(cursor) }
                ?.groupBy { contact -> contact.lookupKey }
                ?.map { contacts ->
                    val allNumbers = contacts.value.map { it.numbers }.flatten()
                    contacts.value.first().apply {
                        numbers.clear()
                        numbers.addAll(allNumbers)
                    }
                } ?: listOf()

        val realm = Realm.getDefaultInstance()
        val recipients = realm.where(Recipient::class.java).findAll()

        realm.executeTransaction {
            realm.delete(Contact::class.java)

            contacts = realm.copyToRealm(contacts)

            // Update all the recipients with the new contacts
            val updatedRecipients = recipients.map { recipient ->
                recipient.apply {
                    contact = contacts.firstOrNull {
                        it.numbers.any { PhoneNumberUtils.compare(recipient.address, it.address) }
                    }
                }
            }

            realm.insertOrUpdate(updatedRecipients)
        }
        realm.close()
    }

}