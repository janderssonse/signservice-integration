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
package se.idsec.signservice.integration.config.impl;

import org.apache.commons.lang.StringUtils;

import lombok.extern.slf4j.Slf4j;
import se.idsec.signservice.integration.certificate.impl.SigningCertificateRequirementsValidator;
import se.idsec.signservice.integration.config.IntegrationServiceConfiguration;
import se.idsec.signservice.integration.core.validation.AbstractInputValidator;
import se.idsec.signservice.integration.core.validation.ValidationResult;
import se.idsec.signservice.integration.document.impl.VisiblePdfSignatureRequirementValidator;
import se.idsec.signservice.integration.security.EncryptionParameters;

/**
 * Validator for {@link IntegrationServiceConfiguration} objects.
 * <p>
 * Note: This implementation is package-private and is used internally by {@link DefaultConfigurationManager}.
 * </p>
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
@Slf4j
class IntegrationServiceConfigurationValidator extends
  AbstractInputValidator<IntegrationServiceConfiguration, Void> {
  
  /** Validator for EncryptionParameters. */
  private EncryptionParametersValidator encryptionParametersValidator = new EncryptionParametersValidator();
  
  /** Validator for SigningCertificateRequirements. */
  private SigningCertificateRequirementsValidator defaultCertificateRequirementsValidator = 
      new SigningCertificateRequirementsValidator();
  
  /** Validator for VisiblePdfSignatureRequirement. */
  private VisiblePdfSignatureRequirementValidator defaultVisiblePdfSignatureRequirementValidator =
      new VisiblePdfSignatureRequirementValidator();

  /** {@inheritDoc} */
  @Override
  public ValidationResult validate(final IntegrationServiceConfiguration object, final String objectName, final Void hint) {
    if (object == null) {
      throw new IllegalArgumentException("IntegrationServiceConfiguration object must not be null");
    }    
    final ValidationResult result = new ValidationResult(objectName);
    
    // Policy
    if (StringUtils.isBlank(object.getPolicy())) {
      result.rejectValue("policy", "Missing policy name");
    }
    
    // DefaultSignRequesterID
    if (StringUtils.isBlank(object.getDefaultSignRequesterID())) {
      log.warn("Service configuration '{}' does not specify a defaultSignRequesterID value", object.getPolicy());
    }
    
    // DefaultReturnUrl
    if (StringUtils.isBlank(object.getDefaultReturnUrl())) {
      log.warn("Service configuration '{}' does not specify a defaultReturnUrl value", object.getPolicy());
    }
    
    // DefaultSignatureAlgorithm
    if (StringUtils.isBlank(object.getDefaultDestinationUrl())) {
      result.rejectValue("defaultSignatureAlgorithm", "Missing defaultSignatureAlgorithm");
    }    
    
    // SignServiceID
    if (StringUtils.isBlank(object.getSignServiceID())) {
      result.rejectValue("signServiceID", "Missing signServiceID");
    }
    
    // DefaultDestinationUrl
    if (StringUtils.isBlank(object.getDefaultDestinationUrl())) {
      result.rejectValue("defaultDestinationUrl", "Missing defaultDestinationUrl");
    }
    
    // DefaultCertificateRequirements
    if (object.getDefaultCertificateRequirements() == null) {
      result.rejectValue("defaultCertificateRequirements", "Missing defaultCertificateRequirements");
    }
    else if (object.getDefaultCertificateRequirements().getCertificateType() == null) {
      result.rejectValue("defaultCertificateRequirements.certificateType", "Missing certificateType in defaultCertificateRequirements");
    }
    else if (object.getDefaultCertificateRequirements().getAttributeMappings() == null 
        || object.getDefaultCertificateRequirements().getAttributeMappings().isEmpty()) {
      result.rejectValue("defaultCertificateRequirements.attributeMappings", "Missing attributeMappings in defaultCertificateRequirements");
    }
    else {
      result.setFieldErrors(this.defaultCertificateRequirementsValidator.validate(
        object.getDefaultCertificateRequirements(), "defaultCertificateRequirements", null));
    }
    
    // DefaultVisiblePdfSignatureRequirement
    // Also checks the PdfSignatureImageTemplates
    if (object.getDefaultVisiblePdfSignatureRequirement() != null) {
      result.setFieldErrors(this.defaultVisiblePdfSignatureRequirementValidator.validate(
        object.getDefaultVisiblePdfSignatureRequirement(), "defaultVisiblePdfSignatureRequirement", object));
    }
    
    // DefaultEncryptionParameters
    if (object.getDefaultEncryptionParameters() == null) {
      result.rejectValue("defaultEncryptionParameters", "Missing defaultEncryptionParameters");
    }
    else {
      result.setFieldErrors(this.encryptionParametersValidator.validate(
        object.getDefaultEncryptionParameters(), "defaultEncryptionParameters", null));
    }
    
    // SignatureCertificate
    if (object.getSignatureCertificate() == null) {
      result.rejectValue("signatureCertificate", "Missing signatureCertificate");
    }
    
    // SignServiceCertificates
    if (object.getSignServiceCertificates() == null || object.getSignServiceCertificates().isEmpty()) {
      result.rejectValue("signServiceCertificates", "The signServiceCertificates list must be non-empty");
    }
    
    // SigningCredential
    if (object.getSigningCredential() == null) {
      result.rejectValue("signingCredential", "Missing signingCredential");
    }
    else if (object.getSigningCredential().getPrivateKey() == null) {
      result.rejectValue("signingCredential.privateKey", "No private key available in signingCredential");
    }
    else if (object.getSigningCredential().getSigningCertificate() == null) {
      result.rejectValue("signingCredential.signingCertificate", "No signing certificate available in signingCredential");
    }
    
    
    
    return result;
  }
  
  /**
   * Validator for {@link EncryptionParameters}.
   * 
   * @author Martin Lindström (martin@idsec.se)
   * @author Stefan Santesson (stefan@idsec.se)
   */
  private static class EncryptionParametersValidator extends AbstractInputValidator<EncryptionParameters, Void> {

    /** {@inheritDoc} */
    @Override
    public ValidationResult validate(final EncryptionParameters object, final String objectName, final Void hint) {
      final ValidationResult result = new ValidationResult(objectName);
      
      if (StringUtils.isBlank(object.getDataEncryptionAlgorithm())) {
        result.rejectValue("dataEncryptionAlgorithm", "getDataEncryptionAlgorithm is not set");
      }
      if (StringUtils.isBlank(object.getKeyTransportEncryptionAlgorithm())) {
        result.rejectValue("keyTransportEncryptionAlgorithm", "keyTransportEncryptionAlgorithm is not set");
      }      
      return result;
    }
  }

}