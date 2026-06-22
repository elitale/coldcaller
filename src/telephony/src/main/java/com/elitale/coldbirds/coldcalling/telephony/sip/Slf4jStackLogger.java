package com.elitale.coldbirds.coldcalling.telephony.sip;

import gov.nist.core.StackLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Bridges JAIN-SIP's {@link StackLogger} onto SLF4J so the stack logs through
 * Logback instead of log4j.
 *
 * <p>The bundled default ({@code gov.nist.core.LogWriter}) hard-depends on
 * {@code org.apache.log4j.*}, which is not on our classpath — instantiating the
 * SIP stack with it throws {@link NoClassDefFoundError}. Installing this logger
 * via {@code CommonLogger.legacyLogger} avoids log4j entirely.
 */
public final class Slf4jStackLogger implements StackLogger {

    private static final Logger LOG = LoggerFactory.getLogger("gov.nist.javax.sip");

    @Override public boolean isLoggingEnabled() {
        return LOG.isErrorEnabled();
    }

    @Override public boolean isLoggingEnabled(final int level) {
        if (level >= TRACE_DEBUG) return LOG.isDebugEnabled();
        if (level >= TRACE_INFO)  return LOG.isInfoEnabled();
        if (level >= TRACE_WARN)  return LOG.isWarnEnabled();
        return LOG.isErrorEnabled();
    }

    @Override public void logDebug(final String message)                       { LOG.debug(message); }
    @Override public void logDebug(final String message, final Exception ex)    { LOG.debug(message, ex); }
    @Override public void logTrace(final String message)                       { LOG.trace(message); }
    @Override public void logInfo(final String message)                        { LOG.info(message); }
    @Override public void logWarning(final String message)                     { LOG.warn(message); }
    @Override public void logError(final String message)                       { LOG.error(message); }
    @Override public void logError(final String message, final Exception ex)    { LOG.error(message, ex); }
    @Override public void logFatalError(final String message)                  { LOG.error(message); }
    @Override public void logException(final Throwable t)                      { LOG.error("SIP stack exception", t); }

    @Override public void logStackTrace() {
        if (LOG.isTraceEnabled()) LOG.trace("SIP stack trace", new Throwable());
    }

    @Override public void logStackTrace(final int traceLevel) {
        logStackTrace();
    }

    @Override public int getLineCount() {
        return 0;
    }

    @Override public String getLoggerName() {
        return LOG.getName();
    }

    @Override public void setBuildTimeStamp(final String buildTimeStamp) { /* no-op */ }
    @Override public void setStackProperties(final Properties properties) { /* no-op */ }
    @Override public void disableLogging() { /* controlled by SLF4J configuration */ }
    @Override public void enableLogging()  { /* controlled by SLF4J configuration */ }
}
