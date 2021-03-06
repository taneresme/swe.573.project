package com.wolower.ui.service.payment;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mastercard.merchant.checkout.ExpressCheckoutApi;
import com.mastercard.merchant.checkout.PostbackApi;
import com.mastercard.merchant.checkout.PreCheckoutDataApi;
import com.mastercard.merchant.checkout.model.ExpressCheckoutRequest;
import com.mastercard.merchant.checkout.model.PaymentData;
import com.mastercard.merchant.checkout.model.Postback;
import com.mastercard.merchant.checkout.model.PreCheckoutData;
import com.mastercard.sdk.core.exceptions.SDKErrorResponseException;
import com.mastercard.sdk.core.models.Error;
import com.mastercard.sdk.core.models.Errors;
import com.wolower.persistence.dao.MasterpassDao;
import com.wolower.persistence.dao.MasterpassExpressCheckoutDao;
import com.wolower.persistence.dao.MasterpassPairingIdDao;
import com.wolower.persistence.dao.MasterpassPrecheckoutDao;
import com.wolower.persistence.enums.PairingIdSources;
import com.wolower.persistence.model.Masterpass;
import com.wolower.persistence.model.MasterpassExpressCheckout;
import com.wolower.persistence.model.MasterpassPairingId;
import com.wolower.persistence.model.MasterpassPrecheckout;
import com.wolower.persistence.model.User;
import com.wolower.ui.config.MasterpassConfig;

@Service
public class MasterpassService {
	private static final Logger LOGGER = LoggerFactory.getLogger(MasterpassService.class);

	private MasterpassConfig config;
	private MasterpassDao masterpassDao;
	private MasterpassPairingIdDao masterpassPairingIdDao;
	private MasterpassPrecheckoutDao masterpassPrecheckoutDao;
	private MasterpassExpressCheckoutDao masterpassExpressCheckoutDao;

	@Autowired
	public MasterpassService(MasterpassConfig config, MasterpassDao masterpassDao,
			MasterpassPairingIdDao masterpassPairingIdDao, MasterpassPrecheckoutDao masterpassPrecheckoutDao,
			MasterpassExpressCheckoutDao masterpassExpressCheckoutDao) {
		this.config = config;
		this.masterpassDao = masterpassDao;
		this.masterpassPairingIdDao = masterpassPairingIdDao;
		this.masterpassPrecheckoutDao = masterpassPrecheckoutDao;
		this.masterpassExpressCheckoutDao = masterpassExpressCheckoutDao;
	}

	public static void masterpassException(Exception ex, Logger logger) {
		if (ex instanceof SDKErrorResponseException) {
			String errorMessage = "";
			SDKErrorResponseException sdkException = (SDKErrorResponseException) ex;

			Errors errors = (Errors) sdkException.getErrorResponse();
			List<Error> errorList = errors.getError();
			for (Error error : errorList) {
				// developer logic to get the error details from Error object.
				errorMessage = "\n" + "Source:" + error.getSource() + "\n" + "ReasonCode:" + error.getReasonCode()
						+ "\n" + "Description:" + error.getDescription() + "\n" + "Recoverable:"
						+ error.getRecoverable();
			}
			logger.error(ExceptionUtils.getStackTrace(ex));
			logger.error("ERROR MESSAGE FROM MASTERPASS: " + errorMessage);
		}
	}

	public void savePairingId(String pairingId, PairingIdSources source, User user, Masterpass masterpass) {
		/* TODO: Do in transaction belows */

		wasteAllPairingIds(user);
		/* Save new one */
		MasterpassPairingId masterpassPairingId = new MasterpassPairingId();
		masterpassPairingId.setPairingId(pairingId);
		masterpassPairingId.setSource(source);
		masterpassPairingId.setUserId(user.getId());
		masterpassPairingId.setMasterpassId(masterpass.getId());
		masterpassPairingIdDao.save(masterpassPairingId);
	}

	public void wasteAllPairingIds(User user) {
		/* We are wasting all pairing Ids. */
		masterpassPairingIdDao.wasteThemAllByUserId(user.getId(), true);
	}

	public PreCheckoutData getPrecheckoutData(User user) {
		try {
			Masterpass masterpass = getMasterpass(user);

			String pairingId = getPairingId(user);
			PreCheckoutData precheckoutData = PreCheckoutDataApi.show(pairingId);

			/* TODO: Do in transaction belows */
			savePairingId(precheckoutData.getPairingId(), PairingIdSources.PRECHECKOUT, user, masterpass);
			savePrecheckout(user, precheckoutData, masterpass);
			return precheckoutData;
		} catch (Exception ex) {
			MasterpassService.masterpassException(ex, LOGGER);
			throw ex;
		}
	}

	public void savePrecheckout(User user, PreCheckoutData precheckoutData, Masterpass masterpass) {
		MasterpassPrecheckout precheckout = new MasterpassPrecheckout();
		precheckout.setConsumerWalletId(precheckoutData.getConsumerWalletId());
		precheckout.setMasterpassId(masterpass.getId());
		precheckout.setPrecheckoutTransactionId(precheckoutData.getPreCheckoutTransactionId());
		precheckout.setUserId(user.getId());
		precheckout.setWalletName(precheckoutData.getWalletName());
		masterpassPrecheckoutDao.save(precheckout);
	}

	public Masterpass getMasterpass(User user) {
		return masterpassDao.findOneByUserIdAndEnabled(user.getId(), true);
	}

	public String getPairingId(User user) {
		MasterpassPairingId pairingId = masterpassPairingIdDao.findOneByUserIdAndWastedOrderByIdDesc(user.getId(),
				false);
		return pairingId.getPairingId();
	}

	public PaymentData expressCheckout(User user, Double amount, String currency) {
		try {
			PreCheckoutData precheckout = getPrecheckoutData(user);
			Masterpass masterpass = getMasterpass(user);
			String pairingId = getPairingId(user);

			ExpressCheckoutRequest expressCheckoutRequest = new ExpressCheckoutRequest().amount(amount)
					.cardId(masterpass.getDefaultCardId()).checkoutId(config.getCheckoutId()).digitalGoods(false)
					.currency(currency).pairingId(pairingId)
					.preCheckoutTransactionId(precheckout.getPreCheckoutTransactionId())
					.shippingAddressId(masterpass.getDefaultShippingAddressId());

			PaymentData paymentData = ExpressCheckoutApi.create(expressCheckoutRequest);
			/* This value returns as null from masterpass */
			paymentData.pspTransactionId(precheckout.getPreCheckoutTransactionId());

			savePairingId(paymentData.getPairingId(), PairingIdSources.EXPRESSCHECKOUT, user, masterpass);
			saveExpressCheckout(user, paymentData, masterpass);

			LOGGER.info("expressCheckout completed. PairingId: %s", pairingId);

			return paymentData;
		} catch (Exception ex) {
			MasterpassService.masterpassException(ex, LOGGER);
			throw ex;
		}
	}

	public void saveExpressCheckout(User user, PaymentData paymentData, Masterpass masterpass) {
		MasterpassExpressCheckout expressCheckout = new MasterpassExpressCheckout();
		expressCheckout.setMasterpassId(masterpass.getId());
		expressCheckout.setUserId(user.getId());
		expressCheckout.setWalletId(paymentData.getWalletId());
		expressCheckout.setWalletName(paymentData.getWalletName());
		masterpassExpressCheckoutDao.save(expressCheckout);
	}

	public void postback(PaymentData paymentData, String currency, String authorizationCode, Boolean paymentSuccessful,
			Double amount) {
		try {
			ZonedDateTime zdt = LocalDateTime.now().atZone(ZoneId.systemDefault());
			Date date = Date.from(zdt.toInstant());

			Postback postback = new Postback().transactionId(paymentData.getPspTransactionId()).currency(currency)
					.paymentCode(authorizationCode).paymentSuccessful(paymentSuccessful).amount(amount)
					.paymentDate(date);

			PostbackApi.create(postback);

			LOGGER.info("postback completed. PairingId");
		} catch (Exception ex) {
			MasterpassService.masterpassException(ex, LOGGER);
			throw ex;
		}
	}

}
