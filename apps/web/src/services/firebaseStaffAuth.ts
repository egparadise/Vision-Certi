import { initializeApp, getApps } from 'firebase/app';
import { getAuth, GoogleAuthProvider, inMemoryPersistence, setPersistence, signInWithPopup, signOut } from 'firebase/auth';

// Firebase's web app configuration is public. Staff authorization is decided by the API's UID mapping.
const app = getApps()[0] ?? initializeApp({
  apiKey: 'AIzaSyClc6_NWlIkOfCp_UlPDiMecbLX-jbAMGk',
  authDomain: 'hahattalk-vision-certi.firebaseapp.com',
  projectId: 'hahattalk',
  appId: '1:898541793848:web:077472cfd3687cab29accd',
});

const auth = getAuth(app);

export async function signInGoogleStaff(): Promise<string> {
  await setPersistence(auth, inMemoryPersistence);
  const result = await signInWithPopup(auth, new GoogleAuthProvider());
  return result.user.getIdToken();
}

export async function signOutGoogleStaff(): Promise<void> {
  await signOut(auth);
}
