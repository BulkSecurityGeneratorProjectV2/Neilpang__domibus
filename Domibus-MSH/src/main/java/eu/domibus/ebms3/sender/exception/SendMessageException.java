/*
 * Copyright 2015 e-CODEX Project
 *
 * Licensed under the EUPL, Version 1.1 or – as soon they
 * will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the
 * Licence.
 * You may obtain a copy of the Licence at:
 * http://ec.europa.eu/idabc/eupl5
 * Unless required by applicable law or agreed to in
 * writing, software distributed under the Licence is
 * distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the Licence for the specific language governing
 * permissions and limitations under the Licence.
 */

package eu.domibus.ebms3.sender.exception;

/**
 * @author Christian Koch, Stefan Mueller
 */
public class SendMessageException extends RuntimeException {

    public SendMessageException() {
    }

    public SendMessageException(final String message) {
        super(message);
    }

    public SendMessageException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public SendMessageException(final Throwable cause) {
        super(cause);
    }

    public SendMessageException(final String message, final Throwable cause, final boolean enableSuppression, final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
