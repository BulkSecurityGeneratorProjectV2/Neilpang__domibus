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

package eu.domibus.common.dao;

import eu.domibus.common.ErrorCode;
import eu.domibus.common.exception.ConfigurationException;
import eu.domibus.common.exception.EbMS3Exception;
import eu.domibus.common.model.configuration.*;
import eu.domibus.common.model.configuration.Process;
import eu.domibus.common.model.org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.AgreementRef;
import eu.domibus.common.model.org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.PartyId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.bind.JAXBException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Christian Koch, Stefan Mueller
 */
public class CachingPModeProvider extends PModeProvider {

    private static final Log LOG = LogFactory.getLog(CachingPModeProvider.class);


    //Dont access directly, use getter instead
    private Configuration configuration;


    //@Transactional(propagation = Propagation.SUPPORTS, noRollbackFor = IllegalStateException.class)
    protected synchronized Configuration getConfiguration() {
        if (this.configuration == null) {
            this.init();
        }
        return this.configuration;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, noRollbackFor = IllegalStateException.class)
    public void init() {
        if (!this.configurationDAO.configurationExists()) {
            throw new IllegalStateException("No processing modes found. To exchange messages, upload configuration file through the web gui.");
        }
        this.configuration = this.configurationDAO.readEager();
    }


    @Override
    //FIXME: only works for the first leg, as sender=initiator
    protected String findLegName(final String agreementRef, final String senderParty, final String receiverParty, final String service, final String action) throws EbMS3Exception {
        final List<LegConfiguration> candidates = new ArrayList<>();
        for (final Process process : this.getConfiguration().getBusinessProcesses().getProcesses()) {
            for (final Party party : process.getInitiatorParties()) {
                if (party.getName().equals(senderParty)) {
                    for (final Party responder : process.getResponderParties()) {
                        if (responder.getName().equals(receiverParty)) {
                            if (process.getAgreement().getValue().equals(agreementRef)) {
                                candidates.addAll(process.getLegs());
                            }
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0001, "No Candidates for Legs found", null, null);
        }
        for (final LegConfiguration candidate : candidates) {
            if (candidate.getService().getName().equals(service) && candidate.getAction().getName().equals(action)) {
                return candidate.getName();
            }
        }
        throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0001, "No matching leg found", null, null);
    }

    @Override
    protected String findActionName(final String action) throws EbMS3Exception {
        for (final Action action1 : this.getConfiguration().getBusinessProcesses().getActions()) {
            if (action1.getValue().equals(action)) {
                return action1.getName();
            }
        }
        throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0001, "No matching action found", null, null);
    }

    @Override
    protected String findServiceName(final eu.domibus.common.model.org.oasis_open.docs.ebxml_msg.ebms.v3_0.ns.core._200704.Service service) throws EbMS3Exception {
        for (final Service service1 : this.getConfiguration().getBusinessProcesses().getServices()) {
            if (service1.getServiceType().equals(service.getType()) && service1.getValue().equals(service.getValue())) {
                return service1.getName();
            }
        }
        throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0001, "No machting service found", null, null);
    }

    @Override
    //@Transactional(propagation = Propagation.SUPPORTS, noRollbackFor = IllegalStateException.class)
    protected String findPartyName(final Collection<PartyId> partyId) throws EbMS3Exception {
        String partyIdType = "";
        for (final Party party : this.getConfiguration().getBusinessProcesses().getParties()) {
            for (final PartyId id : partyId) {
                for (final Identifier identifier : party.getIdentifiers()) {
                    if (id.getType() != null) {
                        partyIdType = id.getType();
                        try {
                            URI.create(partyIdType);
                        } catch (final IllegalArgumentException e) {
                            final EbMS3Exception ex = new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0003, "no matching party found", null, e);
                            ex.setErrorDetail("PartyId " + id.getValue() + " is not a valid URI [CORE] 5.2.2.3");
                            throw ex;
                        }
                    }
                    String identifierPartyIdType = "";
                    if (identifier.getPartyIdType() != null) {
                        identifierPartyIdType = identifier.getPartyIdType().getValue();
                    }

                    if (partyIdType.equals(identifierPartyIdType) && id.getValue().equals(identifier.getPartyId())) {
                        return party.getName();
                    }
                }
            }
        }
        throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0003, "No matching party found", null, null);
    }

    @Override
    protected String findAgreementRef(final AgreementRef agreementRef) throws EbMS3Exception {
        if (agreementRef == null || agreementRef.getValue() == null || agreementRef.getValue().isEmpty()) {
            return ""; //AgreementRef is optional
        }

        for (final Agreement agreement : this.getConfiguration().getBusinessProcesses().getAgreements()) {
            if (( ( agreementRef.getType() == null && "".equals(agreement.getType()) ) || agreement.getType().equals(agreementRef.getType())) && agreementRef.getValue().equals(agreement.getValue())) {
                return agreement.getName();
            }
        }
        throw new EbMS3Exception(ErrorCode.EbMS3ErrorCode.EBMS_0001, "No matching agreementRef found", null, null);
    }

    @Override
    public Party getSenderParty(final String pModeKey) {
        final String partyKey = this.getSenderPartyNameFromPModeKey(pModeKey);
        for (final Party party : this.getConfiguration().getBusinessProcesses().getParties()) {
            if (party.getName().equals(partyKey)) {
                return party;
            }
        }
        throw new ConfigurationException("no matching sender party found with name: " + partyKey);
    }

    @Override
    public Party getReceiverParty(final String pModeKey) {
        final String partyKey = this.getReceiverPartyNameFromPModeKey(pModeKey);
        for (final Party party : this.getConfiguration().getBusinessProcesses().getParties()) {
            if (party.getName().equals(partyKey)) {
                return party;
            }
        }
        throw new ConfigurationException("no matching receiver party found with name: " + partyKey);
    }

    @Override
    public Service getService(final String pModeKey) {
        final String serviceKey = this.getServiceNameFromPModeKey(pModeKey);
        for (final Service service : this.getConfiguration().getBusinessProcesses().getServices()) {
            if (service.getName().equals(serviceKey)) {
                return service;
            }
        }
        throw new ConfigurationException("no matching service found with name: " + serviceKey);
    }

    @Override
    public Action getAction(final String pModeKey) {
        final String actionKey = this.getActionNameFromPModeKey(pModeKey);
        for (final Action action : this.getConfiguration().getBusinessProcesses().getActions()) {
            if (action.getName().equals(actionKey)) {
                return action;
            }
        }
        throw new ConfigurationException("no matching action found with name: " + actionKey);
    }

    @Override
    public Agreement getAgreement(final String pModeKey) {
        final String agreementKey = this.getAgreementRefNameFromPModeKey(pModeKey);
        for (final Agreement agreement : this.getConfiguration().getBusinessProcesses().getAgreements()) {
            if (agreement.getName().equals(agreementKey)) {
                return agreement;
            }
        }
        throw new ConfigurationException("no matching agreement found with name: " + agreementKey);
    }

    @Override
    public LegConfiguration getLegConfiguration(final String pModeKey) {
        final String legKey = this.getLegConfigurationNameFromPModeKey(pModeKey);
        for (final LegConfiguration legConfiguration : this.getConfiguration().getBusinessProcesses().getLegConfigurations()) {
            if (legConfiguration.getName().equals(legKey)) {
                return legConfiguration;
            }
        }
        throw new ConfigurationException("no matching legConfiguration found with name: " + legKey);
    }

    @Override
    public boolean isMpcExistant(final String mpc) {
        for (final Mpc mpc1 : this.getConfiguration().getMpcs()) {
            if (mpc1.getName().equals(mpc)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getRetentionDownloadedByMpcName(final String mpcName) {
        for (final Mpc mpc1 : this.getConfiguration().getMpcs()) {
            if (mpc1.getName().equals(mpcName)) {
                return mpc1.getRetentionDownloaded();
            }
        }

        CachingPModeProvider.LOG.error("No mpc with name: " + mpcName + " found. Assuming message retention of 0 for downloaded messages.");

        return 0;
    }

    @Override
    public int getRetentionUndownloadedByMpcName(final String mpcName) {
        for (final Mpc mpc1 : this.getConfiguration().getMpcs()) {
            if (mpc1.getName().equals(mpcName)) {
                return mpc1.getRetentionUndownloaded();
            }
        }

        CachingPModeProvider.LOG.error("No mpc with name: " + mpcName + " found. Assuming message retention of -1 for undownloaded messages.");

        return -1;
    }

    @Override
    public List<String> getMpcList() {
        final List<String> result = new ArrayList();
        for (final Mpc mpc : this.getConfiguration().getMpcs()) {
            result.add(mpc.getName());
        }
        return result;
    }

    @Override
    public void refresh() {
        this.configuration = null;
        this.getConfiguration(); //reloads the config
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void updatePModes(final byte[] bytes) throws JAXBException {
        super.updatePModes(bytes);
        this.configuration = null;
    }
}
