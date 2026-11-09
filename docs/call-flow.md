# Call flow

- **Status:** decided 2026-11-08 by Nikhil (#8). Drafted by an agent, decisions confirmed by the human.
- **Variant:** **B, the farmer dials an Indian number** (Tone or KooKoo, chosen in #6). Twilio cannot provide an
  Indian number (#4), so B depends on #6. **Fallback:** if no Indian-number provider passes #6 in time, the team falls
  back to variant A (G-One calls you back on paid Twilio); #12 records that cut.
- Other answers (all the drafted defaults): the buyer must say yes before mediation; same-language pairs are avoided in
  the demo and re-voiced if they happen; female voices (Nalini, Kaveri, Poorvi); Nikhil's phone plays the farmer and
  Nishant's the buyer, both consenting, with synthetic personas on screen.

Sources: #4, #5, #34, CONTEXT.md and dump/LEGACY-NOTES.md sections 6 and 7.
★ = the Nov 10 demo must show this step. Names and numbers are synthetic. The assistant voice is female (Timbre
Nalini `hi-IN`, Kaveri `en-IN`, Poorvi `hi-en`), so Hindi lines use feminine verb forms.

## Variants

**A. "G-One calls you back" (fallback).** Both legs are outbound calls to Indian mobiles from a non-Indian
caller ID (paid Twilio, #4), so nobody dials abroad. What starts the callback:

1. ★ **Console button "Request callback"** for a farmer in the demo list. The video says plainly: "In this demo we
   start the callback from the console."
2. ★ **Follow-up from memory.** The farmer's last call gist has an open follow-up ("call me when you find a buyer
   after harvest"). The console shows it with a "Call now" button, and the opening line mentions it. This is the
   returning-farmer moment.
3. **Missed-call trigger** only if #6 picks Tone or KooKoo (an Indian +91 number). The farmer rings, we reject
   without answering, then call back within 10 s. This is a common Indian pattern and costs the farmer nothing. With
   Twilio it would be an international call, so we don't use it there.

Caveat: the farmer's phone shows a foreign number. Don't hide that in the video.

**B. The farmer dials an Indian number (Tone or KooKoo). Chosen.** It needs #6 to confirm a provider. Steps 3 onward are
the same as in A, except for the greeting.

## Numbered flow

1. **Trigger.** A: create the outbound farmer call; its TwiML is `<Connect><Stream>`. B: the inbound webhook returns
   the same TwiML.
2. **Load memory.** Look up `HMAC(number)`, or the callback request's key. A miss means a new farmer.
3. ★ **Greeting.** A new farmer gets the greeting plus the memory notice. A returning farmer first gets a name check.
   Memory is used only after a yes (see #9).
4. **Language.** The farmer leg starts in Hindi (or the profile's language). Prisma listens with `hi-IN,en-IN`. The
   reply language comes from conversation state, not from `detected_language_code`. A farmer who answers in English
   gets English. Hinglish gets `hi-en`.
5. ★ **Intake.** A new farmer gets at most 3 questions. A returning farmer gets 1 confirmation.
6. ★ **Dated reference.** The farmer hears an AGMARKNET line before any buyer joins (wording from CONTEXT.md).
7. ★ **Buyer suggestion.** Suggest at most 2 directory buyers and recommend one. Each suggestion gives name, place,
   the range the directory lists and the buyer's language.
8. ★ **Connect consent.** Ask an explicit yes/no. **Unclear means no.** The LEGACY §7 negation guard applies (नहीं,
   अगर, लेकिन, "but"…). Retry once, then don't dial.
9. **Hold.** The buyer leg rings for 30 s at most. Every 10 s the farmer hears a short line.
10. ★ **Buyer answers.** The buyer hears who is calling, why, and that an AI is on the line translating. **The buyer
    must say yes** before mediation starts. If they say no or the reply is unclear, apologise and end the buyer leg.
11. ★ **Mediation.** Each finished turn goes STT → translate → TTS on the other leg. There is one floor at a time.
    Turns that arrive while the other leg is playing are queued, never dropped. Barge-in stops playback only on the
    speaker's own leg. The assistant speaks for itself only for intros, holds, read-back and endings. Otherwise it
    only translates. It never negotiates for the farmer and never makes offers.
12. ★ **Whisper (farmer leg only).** Whispers happen only at a turn boundary, right after a buyer turn is translated.
    - The **price whisper** fires when the buyer names a price. The LLM extracts the price and code compares it with
      the dated reference.
    - The **caution** fires on a request for an OTP or PIN. The farmer hears the fixed caution first, then the
      translated request. The secret is never echoed.
    - Each whisper is one sentence (about 6 s) and starts with "यह सिर्फ़ आप सुन रहे हैं".
    - Meanwhile the buyer hears "One moment please" once. A farmer who is waiting hears a soft comfort tone, so the
      silence doesn't trigger "hello?" turns.
13. ★ **Deal read-back.** When code sees matching crop, quantity, price and pickup from both sides, it reads the terms
    to each leg in that leg's language. **Both** must give a clear spoken yes. Unclear means retry once. After 120 s
    the draft expires and the deal is marked unconfirmed.
14. ★ **Ending.** End the buyer leg first. Then the farmer gets a private wrap-up and the call ends. If either side
    hangs up, tell the other side and end its leg.
15. **Buyer no-answer.** On busy, no-answer or 30 s timeout, offer the second buyer or a follow-up callback.
16. ★ **After hang-up.** Write the gist, profile updates and follow-ups (#9). The console shows the deal card.

## Mediation rules

- **Raw audio never crosses legs.** Each leg hears only TTS that we generated. This keeps whispers and cautions
  private and lets us screen every turn.
- **Same language on both sides:** still no raw passthrough. Re-voice the transcript as spoken (skip the translation
  LLM call to save latency). For the demo, pair a **Hindi farmer with an English buyer**, so this case doesn't come up.
- Tell the farmer once that answers take a few seconds. Quote the measured number from #29, never "instant".

## Exact first lines

| Moment | Hindi (Devanagari) | Romanized | English |
|---|---|---|---|
| A: callback, new farmer | नमस्ते, मैं जी-वन बोल रही हूँ, किसानों की AI सहायक। आपने कॉल-बैक माँगा था। क्या अभी दो मिनट बात कर सकते हैं? | Namaste, main G-One bol rahi hoon, kisaanon ki AI sahaayak. Aapne call-back maanga tha. Kya abhi do minute baat kar sakte hain? | Hello, this is G-One, an AI assistant for farmers. You asked for a call back. Do you have two minutes now? |
| B: dial-in, new farmer | नमस्ते, जी-वन में आपका स्वागत है। मैं एक AI सहायक हूँ। हिंदी या अंग्रेज़ी, जो आसान हो, उसमें बोलिए। | Namaste, G-One mein aapka swaagat hai. Main ek AI sahaayak hoon. Hindi ya angrezi, jo aasaan ho, usmein boliye. | Welcome to G-One. I'm an AI assistant. Speak Hindi or English, whichever is easier. |
| Memory notice (new, once) | बेहतर मदद के लिए मैं इस बातचीत का छोटा सा नोट रखूँगी। न चाहें तो कभी भी "मुझे भूल जाओ" कहिए। | Behtar madad ke liye main is baatcheet ka chhota sa note rakhoongi. Na chaahein to kabhi bhi "mujhe bhool jao" kahiye. | To help you better, I'll keep a short note of this call. Say "forget me" any time if you don't want that. |
| Returning, step 1 | नमस्ते, जी-वन बोल रही हूँ। क्या मैं सुरेश जी से बात कर रही हूँ? | Namaste, G-One bol rahi hoon. Kya main Suresh ji se baat kar rahi hoon? | Hello, this is G-One. Am I speaking with Suresh ji? |
| Returning, after yes (A, follow-up) | आपने कहा था कि कटाई के बाद प्याज़ के लिए ख़रीदार ढूँढूँ। क्या अभी लगभग बीस क्विंटल लाल प्याज़ बेचना है? | Aapne kaha tha ki kataai ke baad pyaaz ke liye khareedaar dhoondhoon. Kya abhi lagbhag bees quintal laal pyaaz bechna hai? | You asked me to find an onion buyer after harvest. Do you want to sell about twenty quintals of red onion now? |
| Intake 1 | कौन सी फ़सल बेचनी है, और कितनी? | Kaun si fasal bechni hai, aur kitni? | Which crop do you want to sell, and how much? |
| Intake 2 | माल कहाँ है, कौन सा गाँव या ज़िला? | Maal kahaan hai, kaun sa gaanv ya zila? | Where is the produce: which village or district? |
| Intake 3 | आप प्रति क्विंटल कितने भाव की उम्मीद रखते हैं? | Aap prati quintal kitne bhaav ki ummeed rakhte hain? | What price per quintal are you hoping for? |
| Buyer suggestion | पुणे के मेहता एग्रो ट्रेडर्स प्याज़ ख़रीदते हैं। हमारी सूची में उनका भाव {range} रुपये प्रति क्विंटल लिखा है। वे अंग्रेज़ी बोलते हैं, मैं अनुवाद करूँगी। | Pune ke Mehta Agro Traders pyaaz khareedte hain. Hamaari soochi mein unka bhaav {range} rupaye prati quintal likha hai. Ve angrezi bolte hain, main anuvaad karoongi. | Mehta Agro Traders in Pune buy onions. Our list shows {range} rupees per quintal. They speak English; I'll translate. |
| Connect consent | क्या मैं अभी मेहता एग्रो ट्रेडर्स को फ़ोन लगाऊँ? हाँ या ना बोलिए। | Kya main abhi Mehta Agro Traders ko phone lagaaoon? Haan ya na boliye. | Shall I call Mehta Agro Traders now? Please say yes or no. |
| Unclear → no | माफ़ कीजिए, साफ़ नहीं सुना। ठीक है, अभी फ़ोन नहीं लगाती। | Maaf kijiye, saaf nahin suna. Theek hai, abhi phone nahin lagaati. | Sorry, I didn't catch that. All right, I won't call them now. |
| Hold | फ़ोन लगा रही हूँ। घंटी जा रही है, लाइन पर बने रहिए। / अभी भी घंटी जा रही है। | Phone laga rahi hoon. Ghanti ja rahi hai, line par bane rahiye. / Abhi bhi ghanti ja rahi hai. | Calling them now. It's ringing, please stay on the line. / Still ringing. |
| Buyer's first line | नमस्ते, मैं जी-वन हूँ, एक AI सहायक। लासलगाँव के किसान सुरेश जी लगभग बीस क्विंटल लाल प्याज़ बेचना चाहते हैं। मैं बीच में अनुवाद करूँगी। क्या अभी बात कर सकते हैं? | Namaste, main G-One hoon, ek AI sahaayak. Lasalgaon ke kisaan Suresh ji lagbhag bees quintal laal pyaaz bechna chaahte hain. Main beech mein anuvaad karoongi. Kya abhi baat kar sakte hain? | Hello, this is G-One, an AI assistant, calling for Suresh, a farmer near Lasalgaon who wants to sell about twenty quintals of red onion. He speaks Hindi; I will translate between you. Is now a good time? Please say yes or no. |
| Farmer, buyer joined | मेहता जी लाइन पर हैं। अब बोलिए, मैं उन्हें अंग्रेज़ी में बताऊँगी। जवाब में कुछ सेकंड लगेंगे। | Mehta ji line par hain. Ab boliye, main unhein angrezi mein bataoongi. Jawaab mein kuchh second lagenge. | Mr Mehta is on the line. Go ahead; I'll tell him in English. Replies take a few seconds. |
| Price whisper | यह सिर्फ़ आप सुन रहे हैं: यह भाव AGMARKNET पर {date} को लासलगाँव में दर्ज भाव से कम है। | Yeh sirf aap sun rahe hain: yeh bhaav AGMARKNET par {date} ko Lasalgaon mein darj bhaav se kam hai. | Only you can hear this: that offer is below the AGMARKNET Lasalgaon listing for {date}. |
| Caution (fixed) | यह सिर्फ़ आप सुन रहे हैं: पैसे लेने के लिए कभी PIN या OTP नहीं लगता। किसी को मत बताइए। | Yeh sirf aap sun rahe hain: paise lene ke liye kabhi PIN ya OTP nahin lagta. Kisi ko mat bataiye. | Only you can hear this: receiving money never needs a PIN or OTP. Don't share it with anyone. |
| Buyer, during whisper | एक पल रुकिए। | Ek pal rukiye. | One moment please. |
| Deal read-back (farmer) | तय हुआ: बीस क्विंटल लाल प्याज़, {price} रुपये प्रति क्विंटल, {pickup}। यह कानूनी अनुबंध नहीं, बस बात का सार है। क्या यह सही है? हाँ या ना बोलिए। | Tay hua: bees quintal laal pyaaz, {price} rupaye prati quintal, {pickup}. Yeh kaanooni anubandh nahin, bas baat ka saar hai. Kya yeh sahi hai? Haan ya na boliye. | Agreed: twenty quintals of red onion at {price} rupees per quintal, {pickup}. This is a summary, not a contract. Is that correct? Please say yes or no. |
| Ending (farmer) | धन्यवाद सुरेश जी। सौदे का सार मैंने नोट कर लिया है। नमस्ते। | Dhanyavaad Suresh ji. Saude ka saar maine note kar liya hai. Namaste. | Thank you, Suresh ji. I've noted the deal summary. Goodbye. |
| Ending (buyer) | धन्यवाद। जी-वन अब कॉल बंद कर रही है। | Dhanyavaad. G-One ab call band kar rahi hai. | Thank you. G-One is ending the call now. Goodbye. |
| Buyer no-answer | मेहता जी ने फ़ोन नहीं उठाया। क्या मैं शिंदे ट्रेडर्स को लगाऊँ, या बाद में आपको फिर फ़ोन करूँ? | Mehta ji ne phone nahin uthaaya. Kya main Shinde Traders ko lagaaoon, ya baad mein aapko phir phone karoon? | Mehta Agro didn't answer. Shall I try Shinde Traders, or call you back later? |

`{price}`, `{range}`, `{date}` and `{pickup}` are spoken **as words** (Timbre reads numbers badly).
