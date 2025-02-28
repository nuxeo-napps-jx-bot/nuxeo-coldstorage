/*
 * (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Abdoul BA<aba@nuxeo.com>
 */

package org.nuxeo.coldstorage.service;

import java.time.Duration;

import org.nuxeo.coldstorage.ColdStorageConstants.ColdStorageContentStatus;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.ManagedBlob;

/**
 * @since 10.10
 */
public interface ColdStorageService {

    /**
     * Moves the main content associated with the document of the given {@link DocumentRef} to a cold storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     *
     * @implSpec moves the main content to ColdStorage and fires an
     *           {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_MOVED_EVENT_NAME} event.
     * @return the updated document model if the move succeeds
     * @throws NuxeoException if the main content is already in the cold storage, if there is no main content associated
     *             with the given document, or if the user does not have the permissions needed to perform the action.
     */
    DocumentModel moveContentToColdStorage(CoreSession session, DocumentRef documentRef);

    DocumentModel moveToColdStorage(CoreSession session, DocumentRef documentRef);

    /**
     * Requests a retrieval of the cold storage content associated with the document of the given {@link DocumentRef}.
     *
     * @param session the core session
     * @param documentRef the document reference
     * @param restoreDuration the duration that you want your cold storage content to be accessible after restoring it
     * @apiNote This method will initiate a restoration request, calling the {@link Blob#getStream()} during this
     *          process doesn't mean you will get the blob's content.
     * @return the updated document model if the retrieve succeeds
     * @throws NullPointerException if the {@code restoreDuration} parameter is {@code null}
     * @throws NuxeoException if there is no cold storage content associated with the given document, or if it is being
     *             retrieved
     */
    DocumentModel requestRetrievalFromColdStorage(CoreSession session, DocumentRef documentRef,
                                                  Duration restoreDuration);

    DocumentModel retrieveFromColdStorage(CoreSession session, DocumentModel doc, Duration restoreDuration);

    /**
     * Restores the cold content associated with the document of the given {@link DocumentRef} into its main storage.
     * <p/>
     * The permission {@value org.nuxeo.ecm.core.api.security.SecurityConstants#WRITE_COLD_STORAGE} is required.
     *
     * @implSpec This method will rely on the {@link org.nuxeo.ecm.core.blob.BlobProvider#getStatus(ManagedBlob)} to
     *           check if the restore can be done, otherwise it will request a retrieval
     *           {@link #requestRetrievalFromColdStorage(CoreSession, DocumentRef, Duration)}
     * @return the updated document model if the restore succeeds
     * @throws NuxeoException if the cold content is already in the main storage, if there is no cold content associated
     *             with the given document, or if the user does not have the permissions needed to perform the action.
     */
    DocumentModel restoreContentFromColdStorage(CoreSession session, DocumentRef documentRef);

    DocumentModel restoreFromColdStorage(CoreSession session, DocumentRef documentRef);

    /**
     * Restores the main content from ColdStorage.
     *
     * @implSpec: fires a {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_TO_RESTORE_EVENT_NAME}
     *            event.
     * @see #restoreContentFromColdStorage(CoreSession, DocumentRef)
     */
    void restoreMainContent(DocumentModel documentModel);

    /**
     * Move to ColdStorage all duplicated documents with the same content.
     *
     * @param session the session
     * @param documentModel the document model
     */
    void moveDuplicatedBlobToColdStorage(CoreSession session, DocumentModel documentModel);

    /**
     * Checks if the retrieved cold storage contents are available for download.
     *
     * @implSpec: Queries all documents with a cold storage content which are being retrieved, meaning
     *            {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_BEING_RETRIEVED_PROPERTY} is
     *            {@code true}, and it checks if it is available for download. In which case its fires a
     *            {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_AVAILABLE_EVENT_NAME} event.
     * @see #requestRetrievalFromColdStorage(CoreSession, DocumentRef, Duration)
     */
    ColdStorageContentStatus checkColdStorageContentAvailability(CoreSession session);

    /**
     * Checks if the class storage of document moved to ColdStorage has been updated.
     *
     * @implSpec: Queries all documents with a cold storage class to be updated, meaning
     *            {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_CONTENT_STORAGE_CLASS_TO_UPDATED} is
     *            {@code true}, and it checks if it isn't available for download.
     */
    void checkColdStorageClass(CoreSession session);

    /**
     * Gets the number of days where the document's blob is available, once it's retrieved from cold storage.
     *
     * @return the number of days of availability if property
     *         {@value org.nuxeo.coldstorage.ColdStorageConstants#COLD_STORAGE_NUMBER_OF_DAYS_OF_AVAILABILITY_PROPERTY_NAME}
     *         is configured, {@code 1} otherwise.
     */
    Duration getDurationAvailability();

    /**
     * Return the ColStorage rendition blob for the given {@link DocumentModel}.
     * <p>
     * A rendition blob is returned if found.
     *
     * @param documentModel the document to render
     * @return the {@link Blob} object
     * @throws NuxeoException if the rendition doesn't exist.
     * @since 10.10
     */
    Blob getRendition(DocumentModel documentModel, CoreSession session);
}
