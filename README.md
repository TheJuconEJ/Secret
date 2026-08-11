# Секрет

`Секрет` ist ein Paper-Plugin mit konfigurierbaren, humorvollen Todesmechaniken und einem Spieler-Leinen-System. Es benötigt Java 21 und einen aktuellen Paper-Server für Minecraft 1.21.4 oder neuer.

## Kompilieren und installieren

1. Java 21 und Maven installieren.
2. Im Projektordner `mvn clean package` ausführen.
3. Die erzeugte Datei `target/sekret.jar` in den `plugins`-Ordner des Paper-Servers kopieren.
4. Den Server starten. Dabei werden `config.yml` und nach der ersten Leinenbeziehung `leashes.yml` erzeugt.

Die Konfiguration kann anschließend mit `/totdurcherregung reload` neu geladen werden. Dafür wird die Permission `totdurcherregung.admin` benötigt.

## Mechaniken

### Sneaken auf demselben Block

Beim Starten des Sneakens wird geprüft, ob sich ein anderer Spieler innerhalb von `same_block.distance` befindet. Nur der sneakende Spieler stirbt. `sound`, `particle` und `particle_count` steuern die optionalen Effekte.

### Spielerleine

Mit einer normalen Leine in der Haupthand einen Spieler rechtsklicken, um ihn anzuleinen. Ein unsichtbarer Anker erzeugt die sichtbare Leinenverbindung; die angeleinte Person wird ab `leash.max_distance` zum Besitzer gezogen. Nur der gespeicherte Besitzer kann dieselbe Person durch erneutes Rechtsklicken mit einer Leine lösen.

Besitzer und angeleinte Person werden als UUIDs in `plugins/Секрет/leashes.yml` gespeichert. Beim Disconnect, Tod oder Weltenwechsel wird nur die sichtbare Verbindung entfernt. Sobald beide wieder online, lebendig und in derselben Welt sind, wird sie automatisch wiederhergestellt.

### Erregung

Der Monitor prüft im Intervall `excitement.check_interval_ticks` nur Online-Spielerpaare. Bleibt ein Paar näher als `excitement.distance` für mindestens `required_time_seconds`, sterben beide gemeinsam. `chance` ist die Auslösewahrscheinlichkeit pro anschließender Prüfung; `1.0` bedeutet garantiert.

### Stolpern

Die Stolperprüfung läuft ausschließlich bei echter Spielerbewegung und höchstens einmal pro Spieler im konfigurierten `tripping.check_interval_ticks`-Intervall. `chance` ist eine Zahl von `0.0` bis `1.0`. Die kurze Stolperanimation kann über `tripping.animation` deaktiviert werden.

## Nachrichten und Platzhalter

Alle Nachrichten liegen unter `messages` in der `config.yml` und unterstützen Legacy-Farbcodes wie `&c` sowie diese Platzhalter:

- `<player>` – betroffener Spieler
- `<owner>` – Besitzer einer Leine
- `<leashed>` – angeleinte Person

Sound- und Partikelwerte verwenden die aktuellen Bukkit-Namen, etwa `ENTITY_PLAYER_HURT`, `ENTITY_PLAYER_LEVELUP`, `CLOUD` oder `HEART`. Ein leerer Sound- oder Partikelwert beziehungsweise `particle_count: 0` deaktiviert den jeweiligen Effekt.