/*
 * Copyright 2019 IDsec Solutions AB
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
package se.idsec.signservice.integration.security.impl;

import java.util.Map;

import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;
import se.idsec.signservice.integration.config.IntegrationServiceConfiguration;
import se.idsec.signservice.integration.core.impl.CorrelationID;
import se.idsec.signservice.integration.security.IdpMetadataResolver;
import se.idsec.signservice.integration.security.MetadataException;

/**
 * An {@link IdpMetadataResolver} that offers the possibility to use different resolvers for different profiles.
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
@Slf4j
public class ProfileIdpMetadataResolver implements IdpMetadataResolver, InitializingBean {

  /** A map holding metadata resolvers for each profile. */
  private Map<String, IdpMetadataResolver> resolvers;

  /** Optional resolver to use if no resolver is found for a specific profile. */
  private IdpMetadataResolver defaultResolver;

  /** {@inheritDoc} */
  @Override
  public EntityDescriptor resolveMetadata(String entityID, IntegrationServiceConfiguration config) throws MetadataException {

    IdpMetadataResolver resolver = this.resolvers != null
        ? this.resolvers.getOrDefault(config.getPolicy(), this.defaultResolver)
        : this.defaultResolver;

    if (resolver == null) {
      log.error("{}: No metadata resolver exists for policy {}", CorrelationID.id(), config.getPolicy());
      throw new MetadataException("No metadata resolver found for policy " + config.getPolicy());
    }
    return resolver.resolveMetadata(entityID, config);
  }

  /**
   * Adds a mapping of policy names and metadata resolvers.
   * 
   * @param resolvers
   *          resolver mappings
   */
  public void setResolvers(Map<String, IdpMetadataResolver> resolvers) {
    this.resolvers = resolvers;
  }

  /**
   * Sets the default resolver to use.
   * 
   * @param defaultResolver
   *          the default metadata resolver
   */
  public void setDefaultResolver(IdpMetadataResolver defaultResolver) {
    this.defaultResolver = defaultResolver;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    Assert.isTrue((this.resolvers != null && !this.resolvers.isEmpty()) || this.defaultResolver != null,
      "No resolvers have been configured");
  }

}