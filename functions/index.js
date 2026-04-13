"use strict";

const express = require("express");
const admin = require("firebase-admin");
const logger = require("firebase-functions/logger");
const {onRequest} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");

admin.initializeApp();

const db = admin.firestore();
const app = express();
const backendSharedApiKey = defineSecret("BACKEND_SHARED_API_KEY");

app.use(express.json());

function ok(message) {
  return {success: true, message};
}

function fail(message) {
  return {success: false, message};
}

function tokenDocumentId(token) {
  return Buffer.from(token, "utf8").toString("base64url");
}

function isAuthorized(req) {
  const expectedKey = backendSharedApiKey.value() || "";
  if (!expectedKey) {
    return true;
  }
  return req.get("X-API-Key") === expectedKey;
}

function sanitizeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

app.get("/api/health", async (_req, res) => {
  res.status(200).json(ok("healthy"));
});

app.post("/api/device/register", async (req, res) => {
  if (!isAuthorized(req)) {
    return res.status(401).json(fail("Unauthorized"));
  }

  const token = sanitizeString(req.body?.token);
  const packageName = sanitizeString(req.body?.packageName);
  const pushMode = sanitizeString(req.body?.pushMode);
  const platform = sanitizeString(req.body?.platform) || "android";

  if (!token || !packageName || !pushMode) {
    return res.status(400).json(fail("Missing token, packageName, or pushMode"));
  }

  try {
    await db.collection("deviceTokens").doc(tokenDocumentId(token)).set(
      {
        token,
        packageName,
        pushMode,
        platform,
        active: true,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      {merge: true},
    );

    return res.status(200).json(ok("Device token registered"));
  } catch (error) {
    logger.error("device register failed", error);
    return res.status(500).json(fail("Device token registration failed"));
  }
});

app.post("/api/notifications/test", async (req, res) => {
  if (!isAuthorized(req)) {
    return res.status(401).json(fail("Unauthorized"));
  }

  const token = sanitizeString(req.body?.token);
  const symbol = sanitizeString(req.body?.symbol);
  const tab = sanitizeString(req.body?.tab) || "detail";
  const timeframe = sanitizeString(req.body?.timeframe) || "1h";
  const title = sanitizeString(req.body?.title) || `${symbol || "Market"} test signal`;
  const body =
    sanitizeString(req.body?.body) ||
    `Backend-triggered FCM test${symbol ? ` for ${symbol}` : ""}`;

  if (!token) {
    return res.status(400).json(fail("Missing token"));
  }

  try {
    const response = await admin.messaging().send({
      token,
      notification: {title, body},
      data: {
        symbol,
        tab,
        timeframe,
      },
      android: {
        priority: "high",
      },
    });

    await db.collection("pushEvents").add({
      token,
      symbol,
      tab,
      timeframe,
      title,
      body,
      messageId: response,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      kind: "test",
    });

    return res.status(200).json(ok("Test push sent"));
  } catch (error) {
    logger.error("test push failed", error);

    if (error?.code === "messaging/registration-token-not-registered") {
      await db.collection("deviceTokens").doc(tokenDocumentId(token)).set(
        {
          token,
          active: false,
          invalidatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        {merge: true},
      );
      return res.status(410).json(fail("Registration token not registered"));
    }

    return res.status(500).json(fail("Test push failed"));
  }
});

exports.apiV2 = onRequest(
  {
    cors: true,
    region: "asia-southeast1",
    secrets: [backendSharedApiKey],
  },
  app,
);
