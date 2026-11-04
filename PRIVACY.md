# Waveei privacy policy

Last updated: 1 November 2026.

Waveei is a voice keyboard: you speak, and the text goes into the field you are typing in. Speech is never recognized
on the phone. Your voice goes through the Waveei server to a speech model and comes back as text. This page says what
leaves your phone, who handles it, and for how long.

**In short.** The Waveei server keeps no audio, none of what you dictate and no IP address. Nothing you say is used to
train models. With Waveei Tide it keeps your settings, vocabulary and snippets, so your phones share them. What the
app sends beyond running the service, it sends only if you choose to.

## Who we are

Waveei is run by Roman Chernii, 45 Darwin House, Portsmouth PO1 1JW, United Kingdom. Contact: waveei@googlegroups.com,
or Feedback in the app (Home › Feedback).

You can complain to the UK Information Commissioner's Office (ico.org.uk) or to the data protection authority where
you live.

## When you dictate

- **What is sent.** The recording of what you said, cut into pieces, and with it: your vocabulary (names and terms you
  added or corrected), the languages you speak if you chose them, and the style or translation you picked. With live
  words on (Waveei Tide), a few seconds of the recording also go while you speak, to show the words so far. A long
  dictation goes in pieces of about two minutes while you speak; Cancel stops what is still to go, not a piece already
  sent.
- **Where it goes.** To the Waveei server, run by our cloud hosting provider in Belgium (EU), and from there to our
  speech model provider. The model may run in any of its regions, including outside the UK and the EEA.
- **What is kept.** The Waveei server keeps no audio and no text: it handles each piece in memory and forgets it once
  the text is back. The model provider does not use it to train models and does not keep it after making the text;
  its caching is turned off for Waveei. One exception is the provider's own: if its automated checks flag a request as
  possible abuse, it may keep that request for up to 90 days, and its authorised staff may review it.
- **When.** Only after you tap the mic. Nothing is recorded before, and nothing from password fields.

## What the server receives without asking

Only what it needs to run the service you asked for and to keep it safe:

- **Each request.** Your sign-in (an anonymous account, or the one you signed in with), and the request itself. Your
  IP address is used to deliver the request and is not stored.
- **The server's log**, kept for 30 days: for each request a short, one-way hash of the account, sizes, timings,
  the plan and the outcome. No audio, no text, no IP address.
- **App Check.** Each request carries a token from your phone's integrity check (Play Integrity, part of Google Play
  services) that says it comes from the real app on a real phone. The check keeps no attestation behind it. Free
  minutes need it: on a phone it cannot vouch for (no Google Play services, a rooted phone, an emulator), dictation
  needs Waveei Tide.
- **Free minutes.** The server counts the minutes you dictate each week under a short, one-way hash of your account and
  one of this phone (a hash of the Android id, which the app sends only with free-tier requests). Each week's count is
  deleted 35 days after its last use. An account with a confirmed email gets more minutes, unless the email is at a
  throwaway email service: the server checks its domain against a public list of them on each request, and keeps
  neither the email nor the result.

## What the app sends only if you choose

- **App reports** (Home › Privacy › Share app reports). Asked once at setup, with Share and Don't share side by side;
  off unless you choose Share, and you can change it at any time. On, the event log below goes to the Waveei server
  as it happens, with the apps and fields it names: which screens you open and which settings you change, the setup
  steps, how each dictation, the keyboard and the floating dots went and in which app, network changes, errors and
  crashes. With it, the app's version and the phone's details: its model, Android, screen, languages and memory, what
  the app may do (the microphone, notifications, unrestricted battery), the keyboards turned on and the one in use,
  and where the app was installed from. Never your recordings, your words or anything you type. We use them to see
  what works and what fails.
- **Usage statistics**, with app reports on: once a day, how many times each thing happened in the app that day (each
  line of the event log, its numbers and any app's name left out, each key of the keyboard and each tap on a floating
  dot), and the day's crashes and freezes, with where in the app's code they happened. With them, the app's version and
  Android's, and App Check's token; no account and no phone id, so they cannot tell who sent them. Kept for up to 25
  months. Turning app reports off deletes the counts not yet sent.
- **Feedback** (Home › Feedback) sends what you write, with the app's version, Android's and the phone's model, and
  the event log below only if you turn on Attach the event log. Never a recording or a dictated text.
- App reports and Feedback go to the server's log without your account and are kept for 30 days.

## On your phone

The app keeps these in its private storage, never in phone backups:

- your settings, vocabulary, snippets and recent emoji (with Waveei Tide, also kept on the server: see Account);
- History: the last few recordings and transcripts, so you can retry or insert again; you choose how many;
- the clipboard history the keyboard shows: copied texts in memory only, gone when the keyboard stops; copied images
  as files in the app's cache, gone when they leave the list;
- with Images in clipboard on (Settings › Keyboard), a screenshot you took in the last three minutes, offered on the
  keyboard and kept in the cache like a copied image. Only for this the app asks for access to your photos, and it
  reads only screenshots of the last three minutes, only while the keyboard is open;
- the event log: what the app did and how long it took, errors and crashes; never what you dictate, type or copy, nor
  which apps you use. It stays on the phone unless you attach it to Feedback or share app reports;
- with app reports on, the day's usage statistics until they are sent.

Home › Privacy › Delete all local data erases all of them.

## Floating dots (Accessibility service)

The floating dots need Android's Accessibility service, which you turn on yourself after the app says what it does.
With it on, the app:

- reads the text field you type in, to put your dictation at the cursor; for a few minutes after a dictation, a word
  you correct there is added to your vocabulary;
- sees which keyboard and which app are open, to offer a switch to this keyboard and to tap the field you were in;
  when you leave an app it brings your usual keyboard back, and Waveei's again if you return within two minutes;
- with Auto-send on, finds the app's Send button by its label and presses it;
- when an app does not take typed-in text, pastes it through the clipboard.

Of all this, only a corrected word is kept, in your vocabulary on the phone, and it goes to the server with your
dictations as described above. Nothing else on your screen is stored or sent.

## Account

- **Without signing in.** On your first dictation the app creates an anonymous account for you, so the free minutes
  can be counted. It has no name or email. It is deleted 30 days after it was created, and the app then creates a new
  one.
- **Signing in.** With an email and password, for more free minutes and for Waveei Tide. Accounts are kept by our
  account provider in the United States, which also records the IP address and app of each sign-in and keeps those IP
  addresses for a few weeks.
- **Settings sync, with Waveei Tide.** The server keeps your settings, vocabulary and snippets under your account, so
  each phone signed in to it has the same. It keeps one copy, replaced as you change them, in Belgium (EU), until you
  delete the account. What belongs to one phone stays on it: its keyboards, permissions, the dots and their places,
  and what you chose to share.

## Updates

When opened, every few hours, and twice a day while it is closed, the app asks GitHub for the latest release of its
own repository, and About › Check for updates asks at once; Check automatically turns both off. A new version is told
once in a notification, if you allow notifications. GitHub sees a request for the release, with your IP address,
under GitHub's privacy statement; nothing about you goes with it. An update downloads and installs only when you tap
Install.

## Payments

Waveei Tide is sold through Link (Stripe), the merchant of record: Link takes the payment, handles refunds and keeps
the payment details under its own privacy policy. The server keeps only which account a subscription belongs to and
the subscription's status, for as long as the account exists; an ended subscription's status is deleted 35 days
after it ends. When you pay outside the app, the server reads the payment's email from Stripe once, to find the
account with that confirmed email. The plan page reads your renewal date, card brand and last four digits, and
invoices from Stripe each time you open it, and keeps none of them.

## Who handles your data for us

They act only on our instructions, under data-processing terms.

| Who | What for | Where |
|---|---|---|
| Cloud hosting provider | The Waveei server, free-minute counts, Tide's synced settings, the server's log, usage statistics | Belgium (EU); the log in any of its regions |
| Speech model provider | Turning speech into text | Any of its regions, including outside the UK and the EEA |
| Account and app-integrity provider | Accounts, telling the real app from others | Accounts in the United States; the app check in any of its regions |

Where data leaves the UK or the EEA, it goes under the UK–US data bridge and the EU–US Data Privacy Framework, or the
standard contractual clauses (with the UK addendum) in the providers' data-processing terms; ask us for a copy. You can
also ask us for the providers' names.

## Who handles it on their own terms

These decide for themselves how they use what they receive, as independent controllers under their own privacy
policies:

- **Link and Stripe**, for Waveei Tide's payments, as the merchant of record (see Payments).
- **GitHub**, for the app's updates (see Updates).

No advertising, no analytics SDKs, no selling of data.

## Why we may use it

- **To give you the service you asked for:** dictation, your account, free minutes, payments and Tide's settings sync.
- **Our legitimate interest in a safe, working service:** App Check, the server's log, abuse and fraud prevention.
- **Your consent:** app reports and usage statistics, which you can withdraw at any time in Privacy.
- **Your request:** Feedback, and what you attach to it.

## Your rights

You can ask for a copy of your data, also in a machine-readable form to take elsewhere, for its correction or its
deletion, and to restrict or object to its use, by email or through Feedback.
Account › Delete account deletes your account, its subscription records, its synced settings and its free-minute
counts at once; the phone's own free-minute count expires as above. It does not end Waveei Tide for you: within 14
days of subscribing, get your money back first; later, cancel it first. Until then the account is not deleted.

## Changes

We say in the app before any change to what is sent or kept takes effect. The promises that the server keeps no
audio, none of what you dictate and no IP address, and that nothing is used to train models, will not change without
notice.
