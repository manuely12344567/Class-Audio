# Plan de validation — ClasseAudio

Les validations de prévisualisation V2 déjà réussies sont : compilation Android `:app:assembleDebug`, tests JVM `:app:testDebugUnitTest` (5 tests), et compilation WPF .NET 10 avec ciblage Windows depuis le sandbox Linux. Elles ne valident pas l'exécution sur un PC Windows, un installateur, ni les scénarios matériels/réseau ci-dessous. Aucun test sur appareil Android n'a été réalisé.

## 0. Build et démarrage sur Windows

- Compiler avec .NET 10, produire l'installateur x64 et vérifier que `livekit-server.exe` ainsi que sa licence sont installés.
- Démarrer / arrêter le serveur plusieurs fois; vérifier la suppression de la configuration temporaire et l'absence de processus résiduel.
- Vérifier les journaux en cas de ports déjà occupés ou de refus pare-feu.
- Confirmer que le cours fonctionne sans accès Internet une fois l'application installée.

## 1. Découverte réseau et connexion

- PC et téléphone sur le même Wi-Fi privé/sous-réseau; l'hôte Windows est démarré.
- Recompiler l'APK après les évolutions de découverte. Saisir le code et appuyer sur **Rejoindre avec ce code** sans saisir d'URL : le client doit chercher le service mDNS `_classeaudio._tcp.local`, puis utiliser le balayage HTTP borné si mDNS ne trouve rien.
- Vérifier que l'API `/api/v1/session` est seulement sollicitée pour confirmer l'hôte; le code de classe ne doit être envoyé qu'après la découverte et aucune URL n'est affichée/copier par défaut.
- Vérifier le bouton de recherche, l'état « adresse masquée », le panneau manuel replié et le bouton Windows **Copier le code** (qui ne copie que le code élève, jamais le PIN ni l'adresse technique).
- Pour tester la méthode de secours, bloquer temporairement le multicast sur un réseau de banc ou observer le statut de balayage; si mDNS et le scan direct sont bloqués, le panneau de dépannage doit toujours permettre l'IP/port manuels.
- Vérifier 7880/TCP, 7881/TCP, 7882/UDP et 8090/TCP sur le profil Pare-feu Windows **Privé seulement**.
- Tester aussi le cas d'isolation des clients, VLAN ou réseau invité; documenter clairement les cas où aucune connexion directe n'est possible. Une seule paire PC + téléphone suffit à vérifier la découverte; deux téléphones sont nécessaires pour valider le flux audio et la modération.

## 2. Routage et permissions audio

- Téléphone professeur Android avec micro Bluetooth appairé; confirmer que l'entrée sélectionnée est le micro Bluetooth.
- Confirmer que les élèves reçoivent l'audio avec écouteurs filaires/Bluetooth et haut-parleur.
- Élève normal : publication refusée par le serveur même si l'application cliente essaie de publier.
- Demande de parole → autorisation professeur → publication du microphone de cet élève → révocation; confirmer qu'un deuxième élève ne peut pas publier simultanément.
- Déconnexion/reconnexion de l'orateur; confirmer que la permission initiale redevient refusée.
- Tester écho et Larsen avec haut-parleur dans la même salle; mesurer les niveaux et la latence acoustique.

## 3. Qualité et capacité

Sur le PC et le point d'accès Wi-Fi qui seront réellement utilisés, augmenter progressivement : 1, 10, 25, 50, 100, puis environ 200 téléphones. Garder une seule source audio élève active à la fois.

À chaque palier relever :

- charge CPU/mémoire du PC et de l'AP;
- débit montant/descendant, RTT/latence audio, gigue et pertes;
- participants connectés, interruptions et reconnexions;
- comportement Bluetooth, température et batterie des téléphones;
- effet d'une surcharge ou d'un redémarrage du serveur.

La télémétrie par téléphone devra être ajoutée dans les applications Android. Les essais à 200 appareils sont un critère de validation, pas une capacité supposée du seul fait que le serveur démarre.

## 4. Sécurité et confidentialité avant pilote

- Ajouter TLS local avec certificat épinglé par les applications Android; ne pas transmettre le PIN ou les jetons de contrôle en clair sur un Wi-Fi partagé.
- Tester la limitation prototype (8 échecs par minute et par IP/rôle), puis l'auditer avec les endpoints d'administration.
- Vérifier le renouvellement et l'expiration des jetons; ne jamais embarquer la clé API LiveKit dans l'application Android.
- Confirmer qu'aucun enregistrement audio n'est actif et que les journaux ne contiennent ni PIN ni jeton.
- Ouvrir uniquement les ports nécessaires au réseau privé; ne pas exposer le serveur à Internet.
