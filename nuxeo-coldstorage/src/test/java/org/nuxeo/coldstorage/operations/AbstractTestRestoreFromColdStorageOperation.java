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

package org.nuxeo.coldstorage.operations;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.notification.api.NotificationManager;

public abstract class AbstractTestRestoreFromColdStorageOperation extends AbstractTestColdStorageOperation {

    @Inject
    protected NotificationManager notificationManager;

    @Test
    public void shouldBeRestore() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();
        restoreContentFromColdStorage(documentModel);
    }

    @Test
    public void shouldFailBeingRestored() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        transactionalFeature.nextTransaction();
        documentModel.refresh();
        restoreContentFromColdStorage(documentModel);
        // request a retrieval for a second time
        try {
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because the cold storage content is being restored.");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

    @Test
    public void shouldFailRestoreNoColdStorageContent() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        try {
            // request a restore from the cold storage
            restoreContentFromColdStorage(documentModel);
            fail("Should fail because there is no cold storage content associated to this document.");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

    @Test
    public void shouldMakeRestoreImmediatelyTwice() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        transactionalFeature.nextTransaction();

        Blob blobContent;
        for (int i = 0; i < 10; i++) {
            documentModel = session.getDocument(documentModel.getRef());
            // first make the move to cold storage
            try (OperationContext context = new OperationContext(session)) {
                context.setInput(documentModel);
                DocumentModel updatedDocModel = (DocumentModel) automationService.run(context, MoveToColdStorage.ID);
                checkMoveContent(List.of(documentModel), List.of(updatedDocModel));
            }

            transactionalFeature.nextTransaction();
            documentModel = session.getDocument(documentModel.getRef());
            // the ColdStorage facet should exist
            assertTrue(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

            blobContent = (Blob) documentModel.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
            assertNotNull(blobContent);

            // Restore the document content
            try (OperationContext context = new OperationContext(session)) {
                context.setInput(documentModel);
                documentModel = (DocumentModel) automationService.run(context, RestoreFromColdStorage.ID);
            }

            transactionalFeature.nextTransaction();
            documentModel = session.getDocument(documentModel.getRef());

            // we shouldn't have any ColdStorage content
            assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

            // check main blobs
            blobContent = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
            assertNotNull(blobContent);
            assertEquals(FILE_CONTENT, blobContent.getString());
        }
    }

    protected void restoreContentFromColdStorage(DocumentModel documentModel) throws OperationException, IOException {
        try (OperationContext context = new OperationContext(session)) {
            context.setInput(documentModel);
            documentModel = (DocumentModel) automationService.run(context, RestoreFromColdStorage.ID);
        }

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        checkRestoreContent(documentModel);

        // check the common restore notification
        String username = NotificationConstants.USER_PREFIX + session.getPrincipal().getName();
        List<String> subscriptions = notificationManager.getSubscriptionsForUserOnDocument(username, documentModel);
        assertTrue(subscriptions.contains(ColdStorageConstants.COLD_STORAGE_CONTENT_RESTORED_NOTIFICATION_NAME));
        assertFalse(subscriptions.contains(ColdStorageConstants.COLD_STORAGE_CONTENT_AVAILABLE_NOTIFICATION_NAME));
    }

    protected void checkRestoreContent(DocumentModel documentModel) throws IOException {
        // check the document content
        assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

        // check main blobs
        Blob fileContent = (Blob) documentModel.getPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY);
        assertEquals(FILE_CONTENT, fileContent.getString());

        // we shouldn't have any ColdStorage content
        assertFalse(documentModel.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));
    }

}
