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
package se.idsec.signservice.integration.error;

public abstract class SignServiceIntegrationException extends Exception {

  /** For serializing. */
  private static final long serialVersionUID = 8097401935848375253L;

  public SignServiceIntegrationException(String message) {
    super(message);
  }
  
  public SignServiceIntegrationException(String message, Throwable cause) {
    super(message, cause);
  }

}