# Plan de validation — ClasseAudio

Aucun test ci-dessous n'est déclaré réussi à ce jour.

## 0. Build et démarrage sur Windows

- Compiler avec .NET 10, produire l'installateur x64 et vérifier que `livekit-server.exe` ainsi que sa licence sont installés.
- Démarrer / arrêter le serveur plusieurs fois; vérifier la suppression de la configuration temporaire et l'absence de processus résiduel.
- Vérifier les journaux en cas de ports déjà occupés ou de refus pare-feu.
- Confirmer que le cours fonctionne sans accès Internet une fois l'application installée.

## 1. Réseau local, deux téléphones

- PC et téléphones sur le même SSID et le même sous-réseau.
- Découvrir `_classeaudio._tcp.local`; refaire le test avec multicast bloqué et saisir l'IP/port manuellement.
- Vérifier 7880/TCP, 7881/TCP, 7882/UDP et 8090/TCP sur le profil Pare-feu Windows **Privé seulement**.
- Tester aussi le cas d'isolation des clients, VLAN ou réseau invité; documenter clairement les cas où aucune connexion directe n'est possible.

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
