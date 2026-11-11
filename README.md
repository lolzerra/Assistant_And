# Assistant_And

Assistant Android personnel, client du site **Chat IA** auto-hébergé (Qwen 3.8 via llama.cpp, Whisper, Piper).

- Remplace l'assistant du téléphone : appui long sur le bouton d'alimentation ou d'accueil.
- Voit l'écran : Android fournit une capture à l'ouverture (option « Utiliser une capture d'écran »).
- Dictée (Whisper) et réponses lues à voix haute (Piper), sur le serveur de la maison.
- Photos de la galerie ou de l'appareil photo.
- Consignes de comportement, modèle, réflexion et recherche web réglables.
- Mises à jour depuis les Releases de ce dépôt.

## Installation

1. Télécharger le dernier `Assistant_And-x.y.z.apk` dans **Releases** et l'installer.
2. Ouvrir l'appli, saisir l'adresse du site (réseau local ou VPN) et se connecter avec son compte Chat IA.
3. Android : *Paramètres → Applis → Applis par défaut → Assistant numérique* → **Assistant**, et activer « Utiliser une capture d'écran ».

## Publier une version

Une étiquette `vX.Y.Z` poussée sur GitHub déclenche la compilation, la signature et la publication de l'APK.
La clé de signature est dans les secrets du dépôt (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), jamais dans le code.
