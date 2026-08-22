# Stockfish Overlay for Android

Minimalny projekt aplikacji Android/Kotlin przeznaczony do budowania bezpośrednio przez GitHub Actions.

## Co działa

- uprawnienie „Wyświetlanie nad innymi aplikacjami”,
- ruchoma nakładka,
- ręczne podanie pozycji w FEN,
- do 10 ruchów przez UCI MultiPV,
- pasek oceny,
- uruchamianie/zatrzymywanie overlay,
- automatyczne budowanie `app-debug.apk` w GitHub Actions.

## Stockfish

Projekt celowo nie zawiera binarki silnika. Dodaj Android ARM64 Stockfish do:

`app/src/main/jniLibs/arm64-v8a/libstockfish.so`

Aplikacja próbuje uruchomić ten plik jako proces UCI.

## GitHub – jak dostać APK

1. Utwórz nowe repozytorium.
2. Wrzuć całą zawartość tego katalogu do repozytorium.
3. Wejdź w zakładkę **Actions**.
4. Uruchom workflow **Build Android APK** lub zrób commit do `main`.
5. Po udanym buildzie otwórz jego stronę i pobierz artifact `stockfish-overlay-debug`.
6. W ZIP-ie artifactu będzie `app-debug.apk`.

## Następny etap

Do pełnej wersji potrzebne są jeszcze:

- automatyczne przechwytywanie obrazu przez MediaProjection,
- wykrywanie położenia planszy,
- rozpoznawanie figur/pól,
- rysowanie strzałek bezpośrednio nad polami planszy,
- automatyczne wykrywanie ruchu i ponowna analiza,
- ustawienia przezroczystości i LIVE ON/OFF.

## Gradle Wrapper

Repozytorium nie wymaga `gradlew` do builda w GitHub Actions. Workflow instaluje dokładnie Gradle 9.5.0 przez `gradle/actions/setup-gradle` i wywołuje `gradle :app:assembleDebug`.
