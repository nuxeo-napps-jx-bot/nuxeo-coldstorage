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
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.junit.Test;
import org.nuxeo.coldstorage.ColdStorageConstants;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.thumbnail.ThumbnailService;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.runtime.test.runner.Deploy;

/**
 * @since 11.0
 */
public abstract class AbstractTestMoveColdStorageOperation extends AbstractTestColdStorageOperation {

    @Inject
    protected CoreSession session;

    @Inject
    protected ThumbnailService thumbnailService;

    @Test
    public void shouldFailWithoutRightPermissions() throws OperationException, IOException {
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);

        try {
            CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
            moveContentToColdStorage(userSession, documentModel);
            fail("Should fail because the user does not have permissions to move document to cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldMoveToColdStorage() throws OperationException, IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("john", SecurityConstants.READ, true), //
                new ACE("john", SecurityConstants.WRITE, true), //
                new ACE("john", SecurityConstants.WRITE_COLD_STORAGE, true) };
        DocumentModel documentModel = createFileDocument(session, true, aces);

        CoreSession userSession = CoreInstance.getCoreSession(documentModel.getRepositoryName(), "john");
        moveContentToColdStorage(userSession, documentModel);

        // with Administrator
        documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);
    }

    @Test
    public void shouldMoveDocsToColdStorage() throws OperationException, IOException {
        // with regular user with "WriteColdStorage" permission
        ACE[] aces = { new ACE("linda", SecurityConstants.READ, true), //
                new ACE("linda", SecurityConstants.WRITE, true), //
                new ACE("linda", SecurityConstants.WRITE_COLD_STORAGE, true) };

        List<DocumentModel> documents = List.of(createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces), //
                createFileDocument(session, true, aces));

        CoreSession userSession = CoreInstance.getCoreSession(session.getRepositoryName(), "linda");
        moveContentToColdStorage(userSession, documents);

        // with Administrator
        documents = List.of(createFileDocument(session, true), //
                createFileDocument(session, true), //
                createFileDocument(session, true));

        moveContentToColdStorage(session, documents);
    }

    @Test
    public void shouldFailMoveAlreadyInColdStorage() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, true);
        // make a move
        moveContentToColdStorage(session, documentModel);
        try {
            // try to make a second move
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because the content is already in cold storage");
        } catch (NuxeoException e) {
            assertEquals(SC_CONFLICT, e.getStatusCode());
        }
    }

    @Test
    public void shouldFailMoveToColdStorageNoContent() throws OperationException, IOException {
        DocumentModel documentModel = createFileDocument(session, false);
        try {
            moveContentToColdStorage(session, documentModel);
            fail("Should fail because there is no main content associated with the document");
        } catch (NuxeoException e) {
            assertEquals(SC_NOT_FOUND, e.getStatusCode());
        }
    }

    @Test
    @Deploy("org.nuxeo.coldstorage.test:OSGI-INF/test-thumbnail-recomputation-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.thumbnail:OSGI-INF/thumbnail-listener-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.thumbnail:OSGI-INF/thumbnail-core-types-contrib.xml")
    @Deploy("org.nuxeo.ecm.platform.types")
    public void shouldNotRecomputeThumbnail() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        Blob originalThumbnail = thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(originalThumbnail);

        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        Blob thumbnailUpdateOne = thumbnailService.getThumbnail(documentModel, session);
        assertNotNull(thumbnailUpdateOne);
        assertEquals(originalThumbnail.getString(), thumbnailUpdateOne.getString());
    }

    @Test
    public void shouldNotReplaceColdStorageContent() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        try {
            Blob BloThumbnail = thumbnailService.getThumbnail(documentModel, session);
            documentModel.setPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY,
                    (Serializable) BloThumbnail);
            session.saveDocument(documentModel);
            fail("Should fail because the document content can't be updated");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldNotDeleteColdStorageFacet() throws IOException, OperationException {
        DocumentModel documentModel = createFileDocument(session, true);
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();
        documentModel.refresh();

        try {
            documentModel.removeFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME);
            session.saveDocument(documentModel);
            fail("Should fail because the document content can't be updated");
        } catch (NuxeoException e) {
            assertEquals(SC_FORBIDDEN, e.getStatusCode());
        }
    }

    @Test
    public void shouldDeduplicationColdStorageContent() throws IOException, OperationException {
        List<DocumentModel> documents = List.of(session.createDocumentModel("/", "MyFile1", "File"), //
                session.createDocumentModel("/", "MyFile010", "File"), //
                session.createDocumentModel("/", "MyFile700", "File"), //
                session.createDocumentModel("/", "MyFile800", "File"));

        Blob blob = Blobs.createBlob(FILE_CONTENT);
        blob.setDigest(UUID.randomUUID().toString());
        for (DocumentModel documentModel : documents) {
            documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY, (Serializable) blob);
            session.createDocument(documentModel);
            session.saveDocument(documentModel);
        }

        transactionalFeature.nextTransaction();

        // Check if we have the expected duplicated blobs
        String query = String.format("SELECT * FROM Document WHERE file:content/digest = '%s'", blob.getDigest());
        documents = session.query(query);
        assertEquals(4, documents.size());

        DocumentModel documentModel = documents.get(0);

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);
        transactionalFeature.nextTransaction();

        // No duplicated documents after moving the main document to ColdStorage
        documents = session.query(query);
        assertEquals(0, documents.size());

        // Check the ColdStorage content for each duplicated document
        query = String.format("SELECT * FROM Document WHERE coldstorage:coldContent/digest = '%s'", blob.getDigest());
        documents = session.query(query);
        assertNotEquals(0, documents.size());
        checkMoveContents(documentModel, documents);
    }

    @Test
    public void shouldMovedAllVersionsToColdStorage() throws IOException, OperationException {
        List<String> docTitles = List.of("AAA", "BBB", "CCC", "DDD");
        DocumentModel documentModel = createFileDocument(session, true);
        String blobDigest = ((Blob) documentModel.getPropertyValue(
                ColdStorageConstants.FILE_CONTENT_PROPERTY)).getDigest();

        // Create 4 versions
        for (String docTitle : docTitles) {
            documentModel.setPropertyValue("dc:title", docTitle);
            documentModel.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
            documentModel = session.saveDocument(documentModel);
            transactionalFeature.nextTransaction();
            documentModel.refresh();
        }

        assertEquals("0.4", documentModel.getVersionLabel());
        transactionalFeature.nextTransaction();

        // Check if we have the expected number of versions
        String query = String.format(
                "SELECT * FROM Document WHERE  ecm:isVersion = 1 AND "
                        + "ecm:versionVersionableId = '%s' AND file:content/digest = '%s'",
                documentModel.getId(), blobDigest);
        List<DocumentModel> documents = session.query(query);
        assertEquals(4, documents.size());

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();

        // Check if all the versions have been moved to ColdStorage
        documents = session.query(query);
        assertEquals(0, documents.size());

        // Check the ColdStorage content for each version
        query = String.format("SELECT * FROM Document WHERE  ecm:isVersion = 1 AND ecm:versionVersionableId = '%s' "
                + "AND coldstorage:coldContent/digest = '%s'", documentModel.getId(), blobDigest);
        documents = session.query(query);
        assertNotEquals(0, documents.size());
        checkMoveContents(documentModel, documents);
    }

    @Test
    public void shouldMoveVersionsWithSameColdStorageContent() throws IOException, OperationException {
        DocumentModel documentModel = session.createDocumentModel("/", "MyFile1", "File");
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY,
                (Serializable) Blobs.createBlob("Initial Blob"));
        session.createDocument(documentModel);
        documentModel.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
        documentModel = session.saveDocument(documentModel);

        transactionalFeature.nextTransaction();

        // Update the blob content
        documentModel.setPropertyValue(ColdStorageConstants.FILE_CONTENT_PROPERTY,
                (Serializable) Blobs.createBlob(FILE_CONTENT));
        documentModel.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
        documentModel = session.saveDocument(documentModel);

        transactionalFeature.nextTransaction();

        // Update the document title
        documentModel.setPropertyValue("dc:title", "AAA");
        documentModel.putContextData(VersioningService.VERSIONING_OPTION, VersioningOption.valueOf("MINOR"));
        documentModel = session.saveDocument(documentModel);

        transactionalFeature.nextTransaction();

        String blobDigest = ((Blob) documentModel.getPropertyValue(
                ColdStorageConstants.FILE_CONTENT_PROPERTY)).getDigest();

        // Check if we have the expected number of versions
        String query = String.format(
                "SELECT * FROM Document WHERE  ecm:isVersion = 1 AND ecm:versionVersionableId = '%s'",
                documentModel.getId());
        List<DocumentModel> documents = session.query(query);
        assertEquals(3, documents.size());

        query = String.format(
                "SELECT * FROM Document WHERE  ecm:isVersion = 1 "
                        + "AND ecm:versionVersionableId = '%s' AND file:content/digest = '%s'",
                documentModel.getId(), blobDigest);
        documents = session.query(query);
        assertEquals(2, documents.size());

        // first make the move to cold storage
        moveContentToColdStorage(session, documentModel);

        transactionalFeature.nextTransaction();

        // Check if all the versions have been moved to ColdStorage
        documents = session.query(query);
        assertEquals(0, documents.size());

        // Check the ColdStorage content for each version
        query = String.format("SELECT * FROM Document WHERE  ecm:isVersion = 1 AND ecm:versionVersionableId = '%s' "
                + "AND coldstorage:coldContent/digest = '%s'", documentModel.getId(), blobDigest);
        documents = session.query(query);
        assertNotEquals(0, documents.size());
        checkMoveContents(documentModel, documents);
    }

    public void checkMoveContents(DocumentModel documentModel, List<DocumentModel> documents) throws IOException {
        Blob blob = (Blob) documentModel.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
        Blob originalThumbnail = thumbnailService.getThumbnail(documentModel, session);
        for (DocumentModel docModel : documents) {
            DocumentModel document = session.getDocument(docModel.getRef());
            Blob coldContent = (Blob) document.getPropertyValue(ColdStorageConstants.COLD_STORAGE_CONTENT_PROPERTY);
            Blob thumbnailUpdateOne = thumbnailService.getThumbnail(document, session);
            assertEquals(blob.getDigest(), coldContent.getDigest());
            assertTrue(document.hasFacet(ColdStorageConstants.COLD_STORAGE_FACET_NAME));

            // Check the Thumbnail value
            assertEquals(originalThumbnail.getString(), thumbnailUpdateOne.getString());

            // Check ColdStorage content
            assertEquals(blob.getString(), coldContent.getString());
        }
    }
}
