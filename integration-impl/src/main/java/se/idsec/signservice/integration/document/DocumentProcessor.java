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
package se.idsec.signservice.integration.document;

/**
 * Base interface for document processors.
 * 
 * @param <T>
 *          the document type
 * 
 * @author Martin Lindström (martin@idsec.se)
 * @author Stefan Santesson (stefan@idsec.se)
 */
public interface DocumentProcessor<T> {

  /**
   * Gets the document decoder for document objects handled by this procesor.
   * 
   * @return the decoder
   */
  DocumentDecoder<T> getDocumentDecoder();

  /**
   * Gets the document encoder for document objects handled by this processor.
   * 
   * @return the encoder
   */
  DocumentEncoder<T> getDocumentEncoder();

}