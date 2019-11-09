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
package se.idsec.signservice.integration;

/**
 * Enum representing a MIME type for sign messages.
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
public enum SignMessageMimeType {

  /** Mime type for text */
  TEXT("text"),

  /** Mime type for HTML */
  HTML("text/html"),

  /** Mime type for markdown */
  MARKDOWN("text/markdown");

  /**
   * Gets the textual representation of the MIME type.
   * 
   * @return the textual representation of the enum value
   */
  public String getMimeType() {
    return this.mimeType;
  }

  /**
   * Given a MIME type's textual representation, the method returns its enum value.
   * 
   * @param mimeType
   *          the MIME type
   * @return the {@code SignMessageMimeType}, or {@code null} if no match is found
   */
  public static SignMessageMimeType fromMimeType(String mimeType) {
    for (SignMessageMimeType t : SignMessageMimeType.values()) {
      if (t.getMimeType().equals(mimeType)) {
        return t;
      }
    }
    return null;
  }

  /** Mime type value */
  private String mimeType;

  /**
   * Constructor.
   * 
   * @param mimeType
   *          the MIME type
   */
  private SignMessageMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

}