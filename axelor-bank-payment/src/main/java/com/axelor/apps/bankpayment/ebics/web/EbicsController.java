/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2017 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.bankpayment.ebics.web;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.xmlbeans.impl.common.IOUtil;

import com.axelor.apps.ReportFactory;
import com.axelor.apps.bankpayment.db.BankOrderFileFormat;
import com.axelor.apps.bankpayment.db.BankStatementFileFormat;
import com.axelor.apps.bankpayment.db.EbicsBank;
import com.axelor.apps.bankpayment.db.EbicsCertificate;
import com.axelor.apps.bankpayment.db.EbicsRequestLog;
import com.axelor.apps.bankpayment.db.EbicsUser;
import com.axelor.apps.bankpayment.db.repo.EbicsBankRepository;
import com.axelor.apps.bankpayment.db.repo.EbicsCertificateRepository;
import com.axelor.apps.bankpayment.db.repo.EbicsRequestLogRepository;
import com.axelor.apps.bankpayment.db.repo.EbicsUserRepository;
import com.axelor.apps.bankpayment.ebics.certificate.CertificateManager;
import com.axelor.apps.bankpayment.ebics.service.EbicsCertificateService;
import com.axelor.apps.bankpayment.ebics.service.EbicsService;
import com.axelor.apps.bankpayment.exception.IExceptionMessage;
import com.axelor.apps.bankpayment.report.IReport;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.data.Listener;
import com.axelor.data.xml.XMLImporter;
import com.axelor.db.Model;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Context;
import com.google.common.base.Joiner;
import com.google.common.io.Files;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class EbicsController {
	
	@Inject
	private EbicsUserRepository ebicsUserRepo;
	
	@Inject
	private EbicsService ebicsService;
	
	@Inject
	private EbicsBankRepository bankRepo;
	
	@Inject
	private EbicsCertificateService certificateService;
	
	@Inject
	private EbicsCertificateRepository certificateRepo;
	
	@Inject
	private EbicsRequestLogRepository logRepo;
	
	@Transactional
	public void generateCertificate(ActionRequest request, ActionResponse response){
		
		EbicsUser ebicsUser = ebicsUserRepo.find(request.getContext().asType(EbicsUser.class).getId());
		
		if (ebicsUser.getStatusSelect() != EbicsUserRepository.STATUS_WAITING_CERTIFICATE_CONFIG 
				&& ebicsUser.getStatusSelect() != EbicsUserRepository.STATUS_CERTIFICATES_SHOULD_BE_RENEW) {
		      return;
	    }
		
		CertificateManager cm = new CertificateManager(ebicsUser);
		try {
			cm.create();
			ebicsUser.setStatusSelect(EbicsUserRepository.STATUS_WAITING_SENDING_SIGNATURE_CERTIFICATE);
			ebicsUserRepo.save(ebicsUser);
		} catch (GeneralSecurityException | IOException e) {
			e.printStackTrace();
		}
		response.setReload(true);
		
	}
	
	public void generateDn(ActionRequest request, ActionResponse response){
		
		EbicsUser ebicsUser = ebicsUserRepo.find(request.getContext().asType(EbicsUser.class).getId());
		
		response.setValue("dn", ebicsService.makeDN(ebicsUser));

	}
	
	public void sendINIRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendINIRequest(ebicsUser, null);
		}catch (AxelorException e) {
			e.printStackTrace();
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}
		
		response.setReload(true);
		
	}
	
	public void sendHIARequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendHIARequest(ebicsUser, null);
		}catch (AxelorException e) {
			e.printStackTrace();
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}
		
		response.setReload(true);
	}
	
	public void sendHPBRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			X509Certificate[] certificates = ebicsService.sendHPBRequest(ebicsUser, null);
			confirmCertificates(ebicsUser, certificates, response);
		}catch (AxelorException e) {
			e.printStackTrace();
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}
		
		response.setReload(true);
	}
	
	private void confirmCertificates(EbicsUser user, X509Certificate[] certificates, ActionResponse response)  {

		
		try {
			EbicsBank bank = user.getEbicsPartner().getEbicsBank();
			response.setView(ActionView.define("Confirm certificates")
				.model("com.axelor.apps.bankpayment.db.EbicsCertificate")
				.add("form", "ebics-certificate-confirmation-form")
				.param("show-toolbar", "false")
				.param("show-confirm", "false")
				.param("popup-save", "false")
				.param("popup", "true")
				.context("ebicsBank", bank)
				.context("url", bank.getUrl())
				.context("hostId", bank.getHostId())
				.context("e002Hash", DigestUtils.sha256Hex(certificates[0].getEncoded()).toUpperCase())
				.context("x002Hash", DigestUtils.sha256Hex(certificates[1].getEncoded()).toUpperCase())
				.context("certificateE002", certificateService.convertToPEMString(certificates[0]))
				.context("certificateX002", certificateService.convertToPEMString(certificates[1])).map());
		}catch(Exception e) {
			response.setFlash("Error in certificate confirmation ");
		}
			
	}
	
	public void sendSPRRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendSPRRequest(ebicsUser, null);
		}catch (AxelorException e) {
			e.printStackTrace();
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}

		response.setReload(true);
	}
	
	public void sendFULRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			
			MetaFile testDataMetaFile = ebicsUser.getTestDataFile();
			MetaFile testSignatureMetaFile = ebicsUser.getTestSignatureFile();

			BankOrderFileFormat bankOrderFileFormat = ebicsUser.getTestBankOrderFileFormat();
			
			if(testDataMetaFile != null && bankOrderFileFormat != null)  { 

				File testSignatureFile = null;
				
				if(ebicsUser.getEbicsPartner().getEbicsTypeSelect() == EbicsUserRepository.EBICS_TYPE_TS && testSignatureMetaFile != null)  {
					testSignatureFile = MetaFiles.getPath(testSignatureMetaFile).toFile();
				}
				
				ebicsService.sendFULRequest(ebicsUser, ebicsUser.getTestSignatoryEbicsUser(), null, MetaFiles.getPath(testDataMetaFile).toFile(), bankOrderFileFormat.getOrderFileFormatSelect(), testSignatureFile);
			}
			else  {
				response.setFlash(I18n.get(IExceptionMessage.EBICS_TEST_MODE_NOT_ENABLED));
			}
			
		}catch (AxelorException e) {
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}

		response.setReload(true);
	}
	
	public void sendFDLRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {

			BankStatementFileFormat bankStatementFileFormat = ebicsUser.getTestBankStatementFileFormat();
			
			if(ebicsUser.getEbicsPartner().getTestMode() && bankStatementFileFormat != null)  { 
				ebicsService.sendFDLRequest(ebicsUser, null, null, null, bankStatementFileFormat.getStatementFileFormatSelect());
				downloadFile(response, ebicsUser);
			}
			else  {
				response.setFlash(I18n.get(IExceptionMessage.EBICS_TEST_MODE_NOT_ENABLED));
			}

		}catch (AxelorException e) {
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}
		
		response.setReload(true);
	}
	
	public void sendHTDRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendHTDRequest(ebicsUser, null, null, null);
			downloadFile(response, ebicsUser);
		}catch (AxelorException e) {
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}

		response.setReload(true);
	}
	
	public void sendPTKRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendPTKRequest(ebicsUser, null, null, null);
			downloadFile(response, ebicsUser);
		}catch (AxelorException e) {
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}

		response.setReload(true);
	}
	
	public void sendHPDRequest(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = ebicsUserRepo.find( request.getContext().asType(EbicsUser.class).getId());
		
		try {
			ebicsService.sendHPDRequest(ebicsUser, null, null, null);
			downloadFile(response, ebicsUser);
		}catch (AxelorException e) {
			response.setFlash(stripClass(e.getLocalizedMessage()));
		}

		response.setReload(true);
	}
	
	private String stripClass(String msg) {
		
		return msg.replace(AxelorException.class.getName() + ":", "");
	}
	
	public void addCertificates(ActionRequest request, ActionResponse response) throws AxelorException {
		
		Context context = request.getContext();
		
		EbicsBank bank = (EbicsBank)context.get("ebicsBank");
		
		bank = bankRepo.find(bank.getId());
		
		try {
			X509Certificate certificate =  certificateService.convertToCertificate((String)context.get("certificateE002"));
			certificateService.createCertificate(certificate, bank, EbicsCertificateRepository.TYPE_ENCRYPTION);
			
			certificate =  certificateService.convertToCertificate((String)context.get("certificateX002"));
			certificateService.createCertificate(certificate, bank, EbicsCertificateRepository.TYPE_AUTHENTICATION);
			
			
		} catch (CertificateException | IOException e) {
			e.printStackTrace();
			throw new AxelorException(I18n.get("Error in adding bank certificate"), IException.CONFIGURATION_ERROR);
		}
		
		response.setCanClose(true);
	}
	
	public void loadCertificate(ActionRequest request, ActionResponse response) throws AxelorException, CertificateEncodingException, IOException {
		
		EbicsCertificate cert = request.getContext().asType(EbicsCertificate.class);
		
		cert = certificateRepo.find(cert.getId());
		
		byte[] certs = cert.getCertificate();
		
		if (certs != null && certs.length > 0) {
			X509Certificate certificate = EbicsCertificateService.getCertificate(certs, cert.getTypeSelect());
			cert = certificateService.updateCertificate(certificate, cert, true);
			response.setValue("validFrom", cert.getValidFrom());
			response.setValue("validTo", cert.getValidTo());
			response.setValue("issuer", cert.getIssuer());
			response.setValue("subject", cert.getSubject());
			response.setValue("publicKeyModulus", cert.getPublicKeyModulus());
			response.setValue("publicKeyExponent", cert.getPublicKeyExponent());
			response.setValue("fullName", cert.getFullName());
			response.setValue("pemString", cert.getPemString());
			response.setValue("sha2has", cert.getSha2has());
		}
		
	}
	
	public void updateEditionDate(ActionRequest request, ActionResponse response) {
		
		EbicsUser ebicsUser = request.getContext().asType(EbicsUser.class);
		ebicsUser = ebicsUserRepo.find(ebicsUser.getId());
		certificateService.updateEditionDate(ebicsUser);

		response.setReload(true);
		
	}
	
	@Transactional
	public void importEbicsUsers(ActionRequest request, ActionResponse response) {
		
		String config = "/data-import/import-ebics-user-config.xml";
		
		try {
			InputStream inputStream = this.getClass().getResourceAsStream(config);
			File configFile = File.createTempFile("config", ".xml");
			FileOutputStream fout = new FileOutputStream(configFile);
			IOUtil.copyCompletely(inputStream, fout);
			
			Path path = MetaFiles.getPath((String) ((Map) request.getContext().get("dataFile")).get("filePath"));
			File tempDir = Files.createTempDir();
			File importFile = new File(tempDir, "ebics-user.xml");
			Files.copy(path.toFile(), importFile);
			
			XMLImporter importer = new XMLImporter(configFile.getAbsolutePath(), tempDir.getAbsolutePath());
			final StringBuilder log = new StringBuilder();
			Listener listner = new Listener() {
				
				@Override
				public void imported(Integer imported, Integer total) {
					log.append("Total records: " + total + ", Total imported: " + total);
					
				}
				
				@Override
				public void imported(Model arg0) {
				}
				
				@Override
				public void handle(Model arg0, Exception err) {
					log.append("Error in import: " + err.getStackTrace().toString());
				}
			};
			
			importer.addListener(listner);
			
			importer.run();
			
			FileUtils.forceDelete(configFile);
			
			FileUtils.forceDelete(tempDir);
			
			response.setValue("importLog", log.toString());
			
		} catch (IOException e) {
			e.printStackTrace();
		} 
	}
	
	
	public void printCertificates(ActionRequest request, ActionResponse response) throws AxelorException {
		
		EbicsUser ebicsUser = request.getContext().asType(EbicsUser.class);
		
		ArrayList<Long> certIds = new ArrayList<Long>();
		if (ebicsUser.getA005Certificate() != null) {
			certIds.add(ebicsUser.getA005Certificate().getId());
		}
		if (ebicsUser.getE002Certificate() != null) {
			certIds.add(ebicsUser.getE002Certificate().getId());
		}
		if (ebicsUser.getX002Certificate() != null) {
			certIds.add(ebicsUser.getX002Certificate().getId());
		}
		
		if (certIds.isEmpty()) {
			throw new AxelorException(I18n.get(IExceptionMessage.EBICS_MISSING_CERTIFICATES), 1);
		}
		
		String title = I18n.get("EbicsCertificate");
		
		ReportSettings report = ReportFactory.createReport(IReport.EBICS_CERTIFICATE, title + "-${date}${time}");
		report.addParam("CertificateId", Joiner.on(",").join(certIds));
		report.addParam("EbicsUserId", ebicsUser.getId());
		report.toAttach(ebicsUser);
		report.generate();
		
		response.setView(ActionView
				.define(title)
				.add("html", report.getFileLink()).map());
		
	}
	
	private void downloadFile(ActionResponse response, EbicsUser user) {
		
		EbicsRequestLog requestLog = logRepo.all().filter("self.ebicsUser = ?1", user).order("-id").fetchOne();
		
		if (requestLog != null && requestLog.getResponseFile() != null) {
			response.setView(ActionView.define(requestLog.getRequestType())
					.add("html", "ws/rest/" + EbicsRequestLog.class.getCanonicalName() + "/" + requestLog.getId() + "/responseFile/download")
					.param("download", "dtrue")
					.map());
		}
		
	}
	
}
