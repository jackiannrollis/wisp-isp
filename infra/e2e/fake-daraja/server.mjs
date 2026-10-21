// A stand-in for Safaricom Daraja for the end-to-end suite (ADR 0012): production-shaped answers,
// and for every STK Push a success result callback to the CallBackURL of the request, a moment later,
// as the customer entering their M-Pesa PIN would cause. Never used outside the e2e compose override.
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';

const PORT = Number(process.env.PORT ?? 8095);
const CALLBACK_DELAY_MS = Number(process.env.CALLBACK_DELAY_MS ?? 1500);
const pushes = [];

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

const readBody = (req) =>
  new Promise((resolve) => {
    let data = '';
    req.on('data', (chunk) => (data += chunk));
    req.on('end', () => resolve(data));
  });

/** M-Pesa receipt numbers are 10 upper-case characters; a new one per payment (P1). */
const receipt = () => 'E2E' + randomBytes(4).toString('hex').toUpperCase().slice(0, 7);

const timestamp = () => {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return Number(
    `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}` +
      `${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}`,
  );
};

async function sendResult(push) {
  const body = {
    Body: {
      stkCallback: {
        MerchantRequestID: push.merchantRequestId,
        CheckoutRequestID: push.checkoutRequestId,
        ResultCode: 0,
        ResultDesc: 'The service request is processed successfully.',
        CallbackMetadata: {
          Item: [
            { Name: 'Amount', Value: push.amount },
            { Name: 'MpesaReceiptNumber', Value: push.receipt },
            { Name: 'TransactionDate', Value: timestamp() },
            { Name: 'PhoneNumber', Value: Number(push.phone) },
          ],
        },
      },
    },
  };
  try {
    const response = await fetch(push.callbackUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    push.callbackStatus = response.status;
  } catch (error) {
    push.callbackStatus = `failed: ${error.message}`;
  }
  // The callback URL carries the tenant's private token, so only its path shape is logged.
  console.log(`STK ${push.checkoutRequestId}: result callback answered ${push.callbackStatus}`);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://fake-daraja');
  const body = await readBody(req);

  if (url.pathname === '/health') {
    return json(res, 200, { status: 'UP', pushes: pushes.length });
  }
  if (url.pathname === '/__pushes') {
    return json(
      res,
      200,
      pushes.map(({ checkoutRequestId, amount, receipt, callbackStatus }) => ({
        checkoutRequestId,
        amount,
        receipt,
        callbackStatus,
      })),
    );
  }
  if (url.pathname === '/oauth/v1/generate') {
    return json(res, 200, { access_token: 'fake-e2e-access-token', expires_in: '3599' });
  }
  if (url.pathname === '/mpesa/c2b/v2/registerurl') {
    return json(res, 200, {
      OriginatorCoversationID: 'e2e-register',
      ResponseCode: '0',
      ResponseDescription: 'Success',
    });
  }
  if (url.pathname === '/mpesa/stkpush/v1/processrequest') {
    const request = JSON.parse(body || '{}');
    const push = {
      merchantRequestId: `e2e-${randomBytes(4).toString('hex')}`,
      checkoutRequestId: `ws_CO_e2e_${randomBytes(8).toString('hex')}`,
      amount: Number(request.Amount),
      phone: String(request.PhoneNumber ?? ''),
      callbackUrl: request.CallBackURL,
      receipt: receipt(),
      callbackStatus: null,
    };
    pushes.push(push);
    setTimeout(() => void sendResult(push), CALLBACK_DELAY_MS);
    return json(res, 200, {
      MerchantRequestID: push.merchantRequestId,
      CheckoutRequestID: push.checkoutRequestId,
      ResponseCode: '0',
      ResponseDescription: 'Success. Request accepted for processing',
      CustomerMessage: 'Success. Request accepted for processing',
    });
  }
  if (url.pathname === '/mpesa/stkpushquery/v1/query') {
    // As Daraja answers while the customer has not finished: the callback decides.
    return json(res, 500, {
      requestId: 'e2e-query',
      errorCode: '500.001.1001',
      errorMessage: 'The transaction is being processed',
    });
  }
  return json(res, 404, { errorCode: '404.001.01', errorMessage: 'Resource not found' });
});

server.listen(PORT, () => console.log(`Fake Daraja listening on ${PORT}`));
