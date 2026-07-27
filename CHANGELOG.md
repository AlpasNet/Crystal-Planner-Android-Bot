# Changelog

## 1.0.4

- Traduction complète de l’interface, des états, messages d’erreur courants et journaux de synchronisation.
- Ajout des langues : français (`fr`), anglais (`en`), allemand (`de`), espagnol (`es`), tagalog (`tl`), italien (`it`), portugais (`pt`), portugais brésilien (`pt-BR`), japonais (`ja`) et chinois simplifié (`zh-CN`).
- Ajout de `locale_config.xml` pour la sélection de la langue de l’application dans les paramètres Android 13 et versions ultérieures.
- Sur Android 8 à 12, l’application suit automatiquement la langue du système.

## 1.0.3

- Utilisation du logo fourni dans l’en-tête principal de l’application.
- Création des icônes de lancement pour toutes les densités Android.
- Ajout d’une icône adaptative pour Android 8 et versions ultérieures.
- Le logo est également utilisé par l’écran de démarrage système sur les versions Android récentes.

## 1.0.2

- Ajout d’un bouton avec confirmation pour vider les quatre salons Lodestone configurés.
- L’état actif/inactif provient maintenant directement de WorkManager.
- Affichage explicite de l’intervalle et des opérations en cours.
- Le journal précédent est effacé au début de chaque cycle.
- Le journal affiché est rafraîchi automatiquement lorsque le cycle se termine.
- Correction du rafraîchissement après une exécution périodique.


## 1.0.1

- accepte un jeton brut, `Bot ...`, `DISCORD_TOKEN=...`, `DISCORD_BOT_TOKEN=...`, les guillemets et les blocs de code `.env` ;
- supprime les caractères invisibles souvent ajoutés lors d’un copier-coller mobile ;
- ajoute les boutons **Tester le jeton** et **Effacer le jeton** ;
- enregistre automatiquement le jeton lorsqu’un test réussit ;
- ajoute des diagnostics explicites pour HTTP 401, 403 et 429 ;
- adopte le format User-Agent recommandé pour les appels à l’API Discord ;
- ne journalise jamais le contenu du jeton.
