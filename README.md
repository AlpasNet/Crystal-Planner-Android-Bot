# Crystal Planner



* surveillance des catégories **Topics**, **Notices**, **Maintenance** et **Updates** du Lodestone européen ;
* publication de messages Discord via l’API REST Discord v10 ;
* synchronisation des JSON **Events / Polls**, **Rules** et **Guides** ;
* appel facultatif du générateur PHP avant de lire le JSON Events ;
* comparaison stable des données en ignorant les champs d’horodatage ;
* nettoyage des salons avant republication des tableaux ;
* journal local réinitialisé à chaque cycle et rafraîchi automatiquement à la fin ;
* bouton protégé par confirmation pour vider les salons Lodestone ;
* état actif/inactif lu directement depuis WorkManager ;
* planification périodique avec WorkManager ;
* chiffrement du jeton Discord avec Android Keystore.
* intégration du logo Crystal Planner fourni dans l’interface, l’icône du lanceur et l’écran de démarrage Android.
* interface multilingue disponible en dix langues.



## Langues disponibles

L’application utilise automatiquement la langue du téléphone parmi les langues suivantes :

* Français — `fr` ;
* Anglais — `en` ;
* Allemand — `de` ;
* Espagnol — `es` ;
* Tagalog — `tl` ;
* Italien — `it` ;
* Portugais — `pt` ;
* Portugais brésilien — `pt-BR` ;
* Japonais — `ja` ;
* Chinois simplifié — `zh-CN`.



Sur Android 13 et versions ultérieures, la langue peut être choisie indépendamment de celle du téléphone depuis **Paramètres Android > Applications > Crystal Planner > Langue**. Sur Android 8 à 12, Crystal Planner suit la langue principale du système. Le français reste la langue de repli lorsqu’aucune langue prise en charge ne correspond.

## 

## Prérequis

* Android Studio récent ;
* JDK 17 ;
* Android SDK 36 ;
* appareil sous Android 8.0 ou supérieur (`minSdk 26`) ;
* un bot Discord déjà invité sur le serveur avec les permissions nécessaires.

## 

