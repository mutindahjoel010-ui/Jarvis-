const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

const serverTimestamp = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("calendar_events").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("smart_devices").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("smart_devices")
      .doc("dev1")
      .set({
        userId: ALICE_UID,
        name: "Lamp",
        deviceType: "light",
        isOn: true,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
  );
});

test("Authenticated user can create, read, update, and delete own smart devices", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("smart_devices")
    .doc("lamp_1");

  await assertSucceeds(
    ref.set({
      userId: ALICE_UID,
      name: "Living Room Lamp",
      room: "Living Room",
      deviceType: "light",
      isOn: false,
      level: 80,
      targetTemp: 70,
      entityId: "",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  await assertSucceeds(ref.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("smart_devices").get()
  );
  await assertSucceeds(
    ref.update({
      isOn: true,
      level: 100,
      updatedAt: serverTimestamp(),
    })
  );
  await assertSucceeds(ref.delete());
});

test("Cross-user access to smart devices, calendar events, and conversations is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("calendar_events")
      .doc("ev_1")
      .set({
        userId: ALICE_UID,
        title: "Dentist",
        description: "",
        location: "Main St",
        startTimeMillis: 1700000000000,
        endTimeMillis: 1700003600000,
        deviceEventId: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
  );

  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("calendar_events")
      .doc("ev_1")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("calendar_events").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("calendar_events")
      .doc("ev_2")
      .set({
        userId: ALICE_UID,
        title: "Spoof",
        startTimeMillis: 1700000000000,
        endTimeMillis: 1700003600000,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
  );
});

test("Shadow update and ghost fields are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const devRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("smart_devices")
    .doc("dev_shadow");

  await assertFails(
    devRef.set({
      userId: ALICE_UID,
      name: "Plug",
      deviceType: "plug",
      isOn: true,
      isAdmin: true,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );
});

test("Conversation and append-only messages enforce schema and immutability", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("conversations")
    .doc("conv_1");

  await assertSucceeds(
    convRef.set({
      userId: ALICE_UID,
      title: "Morning check-in",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    })
  );

  const msgRef = convRef.collection("messages").doc("msg_1");
  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      role: "user",
      text: "What is on my schedule today?",
      createdAt: serverTimestamp(),
    })
  );

  await assertFails(msgRef.update({ text: "Tampered" }));
  await assertSucceeds(convRef.collection("messages").get());
});
