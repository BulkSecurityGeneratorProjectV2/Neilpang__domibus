package eu.domibus.ebms3.sender;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import eu.domibus.api.exceptions.DomibusCoreErrorCode;
import eu.domibus.api.message.UserMessageException;
import eu.domibus.api.metrics.Metrics;
import eu.domibus.common.ErrorCode;
import eu.domibus.common.MSHRole;
import eu.domibus.common.exception.ConfigurationException;
import eu.domibus.common.exception.EbMS3Exception;
import eu.domibus.common.model.configuration.LegConfiguration;
import eu.domibus.common.model.configuration.Party;
import eu.domibus.common.services.impl.PullContext;
import eu.domibus.common.services.impl.UserMessageHandlerService;
import eu.domibus.ebms3.common.dao.PModeProvider;
import eu.domibus.ebms3.common.model.Error;
import eu.domibus.ebms3.common.model.Messaging;
import eu.domibus.ebms3.common.model.PullRequest;
import eu.domibus.ebms3.common.model.SignalMessage;
import eu.domibus.ebms3.receiver.BackendNotificationService;
import eu.domibus.ebms3.receiver.MSHWebservice;
import eu.domibus.ebms3.receiver.UserMessageHandlerContext;
import eu.domibus.logging.DomibusLogger;
import eu.domibus.logging.DomibusLoggerFactory;
import eu.domibus.logging.DomibusMessageCode;
import eu.domibus.pki.PolicyService;
import eu.domibus.util.MessageUtil;
import org.apache.neethi.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.TransformerException;
import javax.xml.ws.WebServiceException;
import java.io.IOException;
import java.util.Set;

import static com.codahale.metrics.MetricRegistry.name;
import static eu.domibus.api.metrics.Metrics.METRIC_REGISTRY;

/**
 * @author Thomas Dussart
 * @since 3.3
 * <p>
 * Jms listener in charge of sending pullrequest.
 */
@Component
public class PullMessageSender {

    private static final DomibusLogger LOG = DomibusLoggerFactory.getLogger(PullMessageSender.class);
    @Autowired
    private MSHDispatcher mshDispatcher;
    @Autowired
    private EbMS3MessageBuilder messageBuilder;
    @Qualifier("jaxbContextEBMS")
    @Autowired
    private JAXBContext jaxbContext;
    @Autowired
    private UserMessageHandlerService userMessageHandlerService;
    @Autowired
    private BackendNotificationService backendNotificationService;
    @Autowired
    private PModeProvider pModeProvider;
    @Autowired
    private PolicyService policyService;

    private static final Meter outGoingPullRequests = Metrics.METRIC_REGISTRY.meter(name(MSHWebservice.class, "pull.outgoing.pullrequest"));

    private static final Counter pullRequestCount = Metrics.METRIC_REGISTRY.counter(name(MSHWebservice.class, "pull.outgoing.count"));

    @SuppressWarnings("squid:S2583") //TODO: SONAR version updated!
    @JmsListener(destination = "${domibus.jms.queue.pull}", containerFactory = "pullJmsListenerContainerFactory")
    @Transactional(propagation = Propagation.REQUIRED)
    public void processPullRequest(final MapMessage map) {
        outGoingPullRequests.mark();
        pullRequestCount.inc();

        boolean notifiyBusinessOnError = false;
        Messaging messaging = null;
        String messageId = null;

        Timer.Context processPullRequestTimer = METRIC_REGISTRY.timer(name(PullMessageSender.class, "pull.processPullRequest")).time();
        try {
            final String mpc = map.getString(PullContext.MPC);
            final String pMode = map.getString(PullContext.PMODE_KEY);
            notifiyBusinessOnError = Boolean.valueOf(map.getString(PullContext.NOTIFY_BUSINNES_ON_ERROR));
            SignalMessage signalMessage = new SignalMessage();
            PullRequest pullRequest = new PullRequest();
            pullRequest.setMpc(mpc);
            signalMessage.setPullRequest(pullRequest);
            LOG.debug("Sending pull request with mpc " + mpc);
            LegConfiguration legConfiguration = pModeProvider.getLegConfiguration(pMode);
            Party receiverParty = pModeProvider.getReceiverParty(pMode);
            Policy policy;
            try {
                policy = policyService.parsePolicy("policies/" + legConfiguration.getSecurity().getPolicy());
            } catch (final ConfigurationException e) {

                EbMS3Exception ex = new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0010, "Policy configuration invalid", null, e);
                ex.setMshRole(MSHRole.SENDING);
                throw ex;
            }
            SOAPMessage soapMessage = messageBuilder.buildSOAPMessage(signalMessage, null);
            Timer.Context processPullRequestDispathTimer = METRIC_REGISTRY.timer(name(PullMessageSender.class, "pull.dispatchPullRequest")).time();
            final SOAPMessage response = mshDispatcher.dispatch(soapMessage, receiverParty.getEndpoint(), policy, legConfiguration, pMode);
            processPullRequestDispathTimer.stop();
            messaging = MessageUtil.getMessage(response, jaxbContext);
            if (messaging.getUserMessage() == null && messaging.getSignalMessage() != null) {
                Set<Error> error = signalMessage.getError();
                //@thom why do not I have the error inside the message??
                LOG.debug("No message for sent pull request with mpc " + mpc);
                for (Error error1 : error) {
                    LOG.debug(error1.getErrorCode() + " " + error1.getShortDescription());
                }
                return;
            }
            messageId = messaging.getUserMessage().getMessageInfo().getMessageId();
            UserMessageHandlerContext userMessageHandlerContext = new UserMessageHandlerContext();
            Timer.Context pullhandleNewUserMessage = METRIC_REGISTRY.timer(name(PullMessageSender.class, "pull.handleNewUserMessage")).time();
            SOAPMessage acknowlegement = userMessageHandlerService.handleNewUserMessage(pMode, response, messaging, userMessageHandlerContext);
            pullhandleNewUserMessage.stop();
            //send receipt
            Timer.Context pullReceiptDispathTimer = METRIC_REGISTRY.timer(name(PullMessageSender.class, "pull.dispatchPullReceipt")).time();
            mshDispatcher.dispatch(acknowlegement, receiverParty.getEndpoint(), policy, legConfiguration, pMode);
            pullReceiptDispathTimer.stop();

        } catch (TransformerException | SOAPException | IOException | JAXBException | JMSException e) {
            LOG.error(e.getMessage(), e);
            throw new UserMessageException(DomibusCoreErrorCode.DOM_001, "Error handling new UserMessage", e);
        } catch (final EbMS3Exception e) {
            try {
                if (notifiyBusinessOnError && messaging != null) {
                    backendNotificationService.notifyMessageReceivedFailure(messaging.getUserMessage(), userMessageHandlerService.createErrorResult(e));
                }
            } catch (Exception ex) {
                LOG.businessError(DomibusMessageCode.BUS_BACKEND_NOTIFICATION_FAILED, ex, messageId);
            }
            checkConnectionProblem(e);
        } finally {
            processPullRequestTimer.stop();
            pullRequestCount.dec();
        }
    }

    private void checkConnectionProblem(EbMS3Exception e) {
        if (e.getErrorCode() == ErrorCode.EbMS3ErrorCode.EBMS_0005) {
            LOG.warn(e.getErrorDetail());
            LOG.warn(e.getMessage());
        } else {
            throw new WebServiceException(e);
        }
    }
}
