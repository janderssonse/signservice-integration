/*
 * Copyright 2019-2020 IDsec Solutions AB
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
 */
package se.idsec.signservice.integration.document.pdf.signpage;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;

import lombok.extern.slf4j.Slf4j;
import se.idsec.signservice.integration.ExtendedSignServiceIntegrationService;
import se.idsec.signservice.integration.config.IntegrationServiceConfiguration;
import se.idsec.signservice.integration.core.error.ErrorCode;
import se.idsec.signservice.integration.core.error.InputValidationException;
import se.idsec.signservice.integration.core.error.SignServiceIntegrationException;
import se.idsec.signservice.integration.document.DocumentEncoder;
import se.idsec.signservice.integration.document.DocumentProcessingException;
import se.idsec.signservice.integration.document.pdf.PdfDocumentEncoderDecoder;
import se.idsec.signservice.integration.document.pdf.PdfSignaturePage;
import se.idsec.signservice.integration.document.pdf.PdfSignaturePageFullException;
import se.idsec.signservice.integration.document.pdf.PdfSignaturePagePreferences;
import se.idsec.signservice.integration.document.pdf.PreparedPdfDocument;
import se.idsec.signservice.integration.document.pdf.VisiblePdfSignatureRequirement;
import se.idsec.signservice.integration.document.pdf.signpage.impl.PdfSignaturePagePreferencesValidator;
import se.idsec.signservice.integration.document.pdf.utils.PDDocumentUtils;
import se.idsec.signservice.integration.impl.PdfSignaturePagePreparator;
import se.idsec.signservice.utils.Pair;

/**
 * Implementation of the
 * {@link ExtendedSignServiceIntegrationService#preparePdfSignaturePage(String, byte[], PdfSignaturePagePreferences)}
 * method.
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
@Slf4j
public class DefaultPdfSignaturePagePreparator implements PdfSignaturePagePreparator {

  /** Validator for PdfSignaturePagePreferences objects. */
  private PdfSignaturePagePreferencesValidator pdfSignaturePagePreferencesValidator = new PdfSignaturePagePreferencesValidator();

  /** Encoder for PDF documents. */
  static final private DocumentEncoder<byte[]> encoder = new PdfDocumentEncoderDecoder();

  /** {@inheritDoc} */
  @Override
  public PreparedPdfDocument preparePdfSignaturePage(final byte[] pdfDocument,
      final PdfSignaturePagePreferences signaturePagePreferences,
      final IntegrationServiceConfiguration policyConfiguration)
      throws InputValidationException, PdfSignaturePageFullException, SignServiceIntegrationException {

    // We might update the preferences, so make a copy ...
    //
    final PdfSignaturePagePreferences preferences = signaturePagePreferences != null
        ? signaturePagePreferences.toBuilder().build()
        : null;

    // First validate the input ...
    //
    this.validateInput(pdfDocument, preferences, policyConfiguration);

    PDDocument document = null;

    try {
      // Load the PDF document and check if it already has signatures (we assume that there is a PDF signature page and
      // that each signature has a signature image).
      //
      try {
        document = PDDocumentUtils.load(pdfDocument);
      }
      catch (DocumentProcessingException e) {
        throw new InputValidationException("pdfDocument", "Invalid pdfDocument", e);
      }
      final int signatureCount = this.getSignatureCount(document);

      if (preferences.getSignaturePage().getMaxSignatureImages() <= signatureCount) {
        final String msg =
            String.format("PDF signature page already has '%d' sign images - exceeds maximum allowed number", signatureCount);
        log.info("{}", msg);
        if (preferences.isFailWhenSignPageFull()) {
          throw new PdfSignaturePageFullException(msg);
        }
        else {
          return PreparedPdfDocument.builder()
            .policy(policyConfiguration.getPolicy())
            .visiblePdfSignatureRequirement(
              VisiblePdfSignatureRequirement.createNullVisiblePdfSignatureRequirement())
            .build();
        }
      }

      // OK, find out whether we should add a sign page (signatureCount == 0) or whether we
      // should add the signature image to an already existing page.
      //

      // The page number (1-based) where the signature page is inserted.
      int signPagePageNumber;

      if (signatureCount == 0) {
        log.debug("Adding PDF signature page to document ...");
        Pair<PDDocument, Integer> updateResult = this.addSignaturePage(
          document, preferences.getSignaturePage(), preferences.getInsertPageAt());
        document = updateResult.getFirst();
        signPagePageNumber = updateResult.getSecond();
        log.debug("PDF signature page was inserted at page number {}", signPagePageNumber);
      }
      else {
        log.debug("PDF document already contains signatures");
        signPagePageNumber = this.getSignaturePagePosition(document, preferences.getSignaturePage(),
          preferences.getInsertPageAt(), preferences.getExistingSignaturePageNumber());
        log.debug("PDF signature page is located at page number {}", signPagePageNumber);
      }

      // Start creating a visible signature requirement result object ...
      //
      final VisiblePdfSignatureRequirement visiblePdfSignatureRequirement =
          new VisiblePdfSignatureRequirement(preferences.getVisiblePdfSignatureUserInformation());

      visiblePdfSignatureRequirement.setTemplateImageRef(
        preferences.getSignaturePage().getSignatureImageReference());
      visiblePdfSignatureRequirement.setPage(signPagePageNumber);
      visiblePdfSignatureRequirement.setScale(
        preferences.getSignaturePage().getImagePlacementConfiguration().getScale() != null
            ? preferences.getSignaturePage().getImagePlacementConfiguration().getScale()
            : 0);

      // OK, the next step is to calculate where the signature image should be inserted ...
      //
      this.calculateImagePlacement(visiblePdfSignatureRequirement, preferences.getSignaturePage(), signatureCount);

      // Put together the prepared PDF document ...
      //
      PreparedPdfDocument result = new PreparedPdfDocument();
      result.setPolicy(policyConfiguration.getPolicy());
      result.setVisiblePdfSignatureRequirement(visiblePdfSignatureRequirement);
      if (signatureCount == 0) {
        result.setUpdatedPdfDocument(encoder.encodeDocument(PDDocumentUtils.toBytes(document)));
      }
      return result;

    }
    finally {
      PDDocumentUtils.close(document);
    }
  }

  /**
   * Validates the input supplied to
   * {@link #preparePdfSignaturePage(byte[], PdfSignaturePagePreferences, IntegrationServiceConfiguration)}.
   * 
   * @param pdfDocument
   *          the PDF document
   * @param signaturePagePreferences
   *          the signature page preferences
   * @param policyConfiguration
   *          the policy configuration
   * @throws InputValidationException
   *           for validation errors
   */
  private void validateInput(final byte[] pdfDocument,
      final PdfSignaturePagePreferences signaturePagePreferences,
      final IntegrationServiceConfiguration policyConfiguration)
      throws InputValidationException {

    if (pdfDocument == null) {
      throw new InputValidationException("pdfDocument", "Missing pdfDocument");
    }
    if (signaturePagePreferences == null) {
      throw new InputValidationException("signaturePagePreferences", "Missing signaturePagePreferences");
    }
    if (policyConfiguration == null) {
      throw new InputValidationException("policy", "Can not find policy");
    }
    this.pdfSignaturePagePreferencesValidator.validateObject(signaturePagePreferences, "signaturePagePreferences", policyConfiguration);

    // Update the preferences with settings from the config (if needed) ...
    // Note: No checks need to be applied since the validator would have failed if there is something missing.
    //
    if (signaturePagePreferences.getSignaturePageReference() == null && signaturePagePreferences.getSignaturePage() == null) {
      signaturePagePreferences.setSignaturePage(policyConfiguration.getPdfSignaturePages().get(0));
      log.debug("Using default PdfSignaturePage ({}) for policy '{}'",
        signaturePagePreferences.getSignaturePage().getId(), policyConfiguration.getPolicy());
    }
    if (signaturePagePreferences.getSignaturePageReference() != null) {
      signaturePagePreferences.setSignaturePage(policyConfiguration.getPdfSignaturePages().stream()
        .filter(p -> signaturePagePreferences.getSignaturePageReference().equals(p.getId()))
        .findFirst()
        .orElse(null));
    }
  }

  /**
   * Tells how many signatures that have been applied to the supplied document.
   * 
   * @param document
   *          the document to check
   * @return the number of signatures
   * @throws SignServiceIntegrationException
   *           for errors reading the document
   */
  private int getSignatureCount(final PDDocument document) throws SignServiceIntegrationException {
    try {
      return document.getSignatureDictionaries().stream()
        .filter(s -> !"ETSI.RFC3161".equalsIgnoreCase(s.getSubFilter()))
        .collect(Collectors.counting()).intValue();
    }
    catch (IOException e) {
      throw new DocumentProcessingException(new ErrorCode.Code("format-error"), "Failed to list dictionaries of PDF document", e);
    }
  }

  /**
   * Adds a signature page document according to the {@code insertPageAt} parameter.
   * 
   * @param document
   *          the document to update
   * @param signPage
   *          the sign page to add
   * @param insertPageAt
   *          the (one-based) directive where the sign page should be inserted
   * @return a Pair of the updated document and the (one-based) page number where the sign page is located in the
   *         updated document
   * @throws SignServiceIntegrationException
   *           for processing errors
   */
  private Pair<PDDocument, Integer> addSignaturePage(final PDDocument document, final PdfSignaturePage signPage, Integer insertPageAt)
      throws SignServiceIntegrationException {

    final PDDocument signPageDocument = PDDocumentUtils.load(signPage.getContents());
    try {
      int noPages = document.getNumberOfPages();
      int newPagePos = (insertPageAt == null || insertPageAt == 0) ? noPages + 1 : insertPageAt;

      PDDocument updatedDocument = PDDocumentUtils.insertDocument(document, signPageDocument, newPagePos);

      if (signPage.getImagePlacementConfiguration().getPage() == null || signPage.getImagePlacementConfiguration().getPage() == 1) {
        return new Pair<>(updatedDocument, newPagePos);
      }
      else if (signPage.getImagePlacementConfiguration().getPage() == 0) {
        return new Pair<>(updatedDocument, newPagePos + signPageDocument.getNumberOfPages() - 1);
      }
      else {
        return new Pair<>(updatedDocument, newPagePos + signPage.getImagePlacementConfiguration().getPage() - 1);
      }
    }
    finally {
      PDDocumentUtils.close(signPageDocument);
    }
  }

  /**
   * Given a document that already has one or more signatures (and thus has a sign page), the method calculates the page
   * number (one-based) of the sign page.
   * 
   * @param document
   *          the document
   * @param signPage
   *          the sign page parameters
   * @param insertPageAt
   *          the original configuration where the sign page should be inserted
   * @param existingSignaturePageNumber
   *          a given number where the sign page is placed (may be null)
   * @return the page number
   * @throws SignServiceIntegrationException
   *           if the page doesn't contain a sign page
   */
  private int getSignaturePagePosition(final PDDocument document, final PdfSignaturePage signPage,
      final Integer insertPageAt, final Integer existingSignaturePageNumber) throws SignServiceIntegrationException {

    // The total number of pages of the document that contains the signature page.
    final int noTotalPages = document.getNumberOfPages();

    if (existingSignaturePageNumber != null) {
      if (existingSignaturePageNumber > noTotalPages) {
        final String msg = String.format("Invalid value for existingSignaturePageNumber (%d) - Document only has %d pages",
          existingSignaturePageNumber, noTotalPages);
        log.error("{}", msg);
        throw new InputValidationException("signaturePagePreferences.existingSignaturePageNumber", msg);
      }
      return existingSignaturePageNumber;
    }

    final PDDocument signPageDocument = PDDocumentUtils.load(signPage.getContents());
    try {
      // Number of pages of document before the sign page(s) was added.
      final int docNoPages = noTotalPages - signPageDocument.getNumberOfPages();

      if (docNoPages <= 0) {
        // This must mean that the document has signatures but no previous sign page.
        final String msg = "Document has signature(s), but no previous sign page - cannot process";
        log.error("{}", msg);
        throw new DocumentProcessingException(new ErrorCode.Code("pdf"), msg);
      }

      // First page of sign page document is located at document page:
      final int firstSignPageDocPage = (insertPageAt == null || insertPageAt == 0) ? docNoPages + 1 : insertPageAt;

      if (signPage.getImagePlacementConfiguration().getPage() == null || signPage.getImagePlacementConfiguration().getPage() == 1) {
        return firstSignPageDocPage;
      }
      else if (signPage.getImagePlacementConfiguration().getPage() == 0) {
        return firstSignPageDocPage + signPageDocument.getNumberOfPages() - 1;
      }
      else {
        return firstSignPageDocPage + signPage.getImagePlacementConfiguration().getPage() - 1;
      }
    }
    finally {
      PDDocumentUtils.close(signPageDocument);
    }

  }

  /**
   * Calculates the placement (x and y positions) of the signature image based on the current signature count and the
   * placement configuration.
   * 
   * @param visiblePdfSignatureRequirement
   *          the requirement object to update
   * @param signaturePage
   *          the signature page containing the placement configuration
   * @param signatureCount
   *          the signature count
   */
  private void calculateImagePlacement(
      final VisiblePdfSignatureRequirement visiblePdfSignatureRequirement, final PdfSignaturePage signaturePage, final int signatureCount) {
    
    // Note: we don't have to assert that values are set and such. The validators have already checked this.
    //
    final int pageColumns = Optional.ofNullable(signaturePage.getColumns()).map(Integer::intValue).orElse(1);

    visiblePdfSignatureRequirement.setXPosition(
      signaturePage.getImagePlacementConfiguration().getXPosition() 
      + ((signatureCount % pageColumns) * signaturePage.getImagePlacementConfiguration().getXIncrement()));
    
    visiblePdfSignatureRequirement.setYPosition(
      signaturePage.getImagePlacementConfiguration().getYPosition() 
      + ((signatureCount / pageColumns) * signaturePage.getImagePlacementConfiguration().getYIncrement()));
  }

}