const { describe, it, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');

const PROJECT_ID = 'demo-livepilot-rules-test';
const ALICE_UID = 'alice_uid_001';
const BOB_UID = 'bob_uid_002';
const DEVICE_A = 'samsung_sm_s918b_a1b2c3d4';
const DEVICE_B = 'xiaomi_redmi_note13_e5f6g7h8';

let testEnv;

function nowTs() {
  return new Date(Date.now() - 1000);
}

function validDeviceConfig(deviceId = DEVICE_A) {
  const ts = nowTs();
  return {
    deviceId,
    deviceModel: 'SM-S918B',
    deviceManufacturer: 'Samsung',
    apiKeysJson: '[{"id":"k1","label":"Key 1","key":"AIzaTest123","enabled":true}]',
    activeApiKeyId: 'k1',
    autoRotateApiKeys: true,
    liveModel: 'gemini-2.5-flash-native-audio-preview-12-2025',
    agentModel: 'gemini-2.5-flash',
    textModel: 'gemini-2.5-flash',
    deletedModels: ['gemini-1.5-pro'],
    verifyTaskCompletion: true,
    voiceName: 'Kore',
    voiceLanguage: 'BENGALI_BD',
    speakerphoneOn: true,
    autoVisionDefaultOn: false,
    autoVisionIntervalSec: 4,
    showTouchVisualizer: true,
    showFloatingCaptions: true,
    themeMode: 'SYSTEM',
    customVoiceInstructions: 'Reply briefly in Bengali',
    autoChatMode: 'FULL_AUTO_REPLY',
    autoReplyDelivery: 'SILENT_DIRECT_REPLY',
    autoReplyPersona: 'Reply politely in Bengali.',
    sensitiveQuestionGuardEnabled: true,
    handsFreeVoiceEscalationEnabled: true,
    enabledMessagingApps: ['WHATSAPP', 'IMO'],
    createdAt: ts,
    updatedAt: ts,
  };
}

function validPersonalData() {
  const ts = nowTs();
  return {
    title: 'আমার পেশা',
    content: 'আমি একজন সফটওয়্যার ইঞ্জিনিয়ার, ঢাকায় কাজ করি।',
    category: 'কাজ/পেশা',
    shareInAutoReply: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validRoutine() {
  const ts = nowTs();
  return {
    title: 'Morning Brief',
    subtitle: 'News & weather',
    prompt: 'Check Dhaka weather and top news.',
    accent: 'COBALT',
    createdAt: ts,
    updatedAt: ts,
  };
}

function validAskAiMessage(clientId = 1) {
  const ts = nowTs();
  return {
    clientId,
    senderRole: 'user',
    text: 'আজ ঢাকার আবহাওয়া কেমন?',
    modelUsed: 'gemini-2.5-flash',
    timestampLabel: '10:30:00',
    citationsJson: '[]',
    createdAt: ts,
    updatedAt: ts,
  };
}

describe('LivePilot Per-Device Isolated Firestore Rules', () => {
  before(async () => {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        host: '127.0.0.1',
        port: 8085,
        rules: fs.readFileSync('firestore.rules', 'utf8'),
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  it('denies all unauthenticated reads and writes', async () => {
    const unauthDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(unauthDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}`).get());
    await assertFails(unauthDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}`).set(validDeviceConfig()));
    await assertFails(unauthDb.collection(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData`).get());
  });

  it('allows owner to create, read, list, update, and delete device config (API keys & deletedModels isolated per device)', async () => {
    const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
    const deviceARef = aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}`);
    const deviceBRef = aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_B}`);

    await assertSucceeds(deviceARef.set(validDeviceConfig(DEVICE_A)));
    await assertSucceeds(deviceBRef.set(validDeviceConfig(DEVICE_B)));
    await assertSucceeds(deviceARef.get());
    await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/devices`).get());

    // Partial update of deletedModels, API keys, and handsFreeVoiceEscalationEnabled
    await assertSucceeds(
      deviceARef.update({
        deletedModels: ['gemini-1.5-pro', 'gemini-2.0-flash'],
        handsFreeVoiceEscalationEnabled: false,
        updatedAt: nowTs(),
      })
    );

    // Reject immutable createdAt or deviceId tampering
    await assertFails(
      deviceARef.update({
        deviceId: DEVICE_B,
        updatedAt: nowTs(),
      })
    );
    await assertFails(
      deviceARef.update({
        createdAt: new Date(1000),
        updatedAt: nowTs(),
      })
    );
    // Reject shadow field injection
    await assertFails(
      deviceARef.update({
        isAdmin: true,
        updatedAt: nowTs(),
      })
    );

    await assertSucceeds(deviceBRef.delete());
  });

  it('blocks cross-user access to device config and subcollections', async () => {
    const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
    const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

    await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}`).set(validDeviceConfig()));
    await assertSucceeds(
      aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/entry_1`).set(validPersonalData())
    );

    await assertFails(bobDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}`).get());
    await assertFails(bobDb.collection(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData`).get());
    await assertFails(
      bobDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/entry_1`).update({
        content: 'hacked',
        updatedAt: nowTs(),
      })
    );
    await assertFails(bobDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/entry_1`).delete());
  });

  it('validates CRUD on personalData (আমার ডাটা) subcollection', async () => {
    const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
    const entryRef = aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/fact_01`);

    await assertSucceeds(entryRef.set(validPersonalData()));
    await assertSucceeds(entryRef.get());
    await assertSucceeds(
      aliceDb
        .collection(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData`)
        .orderBy('createdAt', 'desc')
        .get()
    );
    await assertSucceeds(
      entryRef.update({
        content: 'আমি ঢাকায় Android ও AI ডেভেলপার।',
        shareInAutoReply: false,
        updatedAt: nowTs(),
      })
    );

    // Invalid empty title rejected
    await assertFails(
      aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/fact_bad`).set({
        ...validPersonalData(),
        title: '',
      })
    );
    // Shadow field rejected
    await assertFails(
      aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/personalData/fact_shadow`).set({
        ...validPersonalData(),
        extraHackField: 'nope',
      })
    );
    await assertSucceeds(entryRef.delete());
  });

  it('validates CRUD on routines and askAiMessages subcollections', async () => {
    const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
    const routineRef = aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/routines/custom_r1`);
    const msgRef = aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/askAiMessages/msg_1`);

    await assertSucceeds(routineRef.set(validRoutine()));
    await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/devices/${DEVICE_A}/routines`).get());
    await assertSucceeds(routineRef.update({ title: 'Updated Brief', updatedAt: nowTs() }));
    await assertSucceeds(routineRef.delete());

    await assertSucceeds(msgRef.set(validAskAiMessage(1)));
    await assertSucceeds(
      aliceDb
        .collection(`users/${ALICE_UID}/devices/${DEVICE_A}/askAiMessages`)
        .orderBy('clientId', 'asc')
        .get()
    );
    // Invalid senderRole rejected
    await assertFails(
      aliceDb.doc(`users/${ALICE_UID}/devices/${DEVICE_A}/askAiMessages/msg_bad`).set({
        ...validAskAiMessage(2),
        senderRole: 'system_hack',
      })
    );
    await assertSucceeds(msgRef.delete());
  });
});
