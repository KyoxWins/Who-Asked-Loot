/*
 * Copyright (c) 2022, RinZ
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.betterdiscordlootlogger;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.UsernameChanged;
import net.runelite.api.events.WidgetLoaded;
import static net.runelite.api.widgets.WidgetID.CHAMBERS_OF_XERIC_REWARD_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.THEATRE_OF_BLOOD_REWARD_GROUP_ID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;
import static net.runelite.http.api.RuneLiteAPI.GSON;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "Better Discord Loot Logger"
)
public class BetterDiscordLootLoggerPlugin extends Plugin
{
	private static final String COLLECTION_LOG_TEXT = "New item added to your collection log: ";
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(".*Valuable drop: ([^<>]+?\\(((?:\\d+,?)+) coins\\))(?:</col>)?");
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of("You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed");
	private static final Pattern COX_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) - (.+)");
	private static final String COX_DUST_MESSAGE_TEXT = "Dust recipients: ";
	private static final String COX_KIT_MESSAGE_TEXT = "Twisted Kit recipients: ";
	private static final Pattern TOB_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) found something special: (.+)");
	private static final Pattern KC_MESSAGE_PATTERN = Pattern.compile("([0-9]+)");

	private boolean shouldSendMessage;
	private boolean notificationStarted;
	enum RaidType
	{
		COX,
		COX_CM,
		TOB,
		TOB_SM,
		TOB_HM
	}

	private RaidType raidType;
	//TODO: Include kc for the other notification types too
	// - Collection log entries
	// - Valuable drops
	// - Pets
	private Integer raidKc;
	private String raidItemName;

	@Inject
	private Client client;

	@Inject
	private BetterDiscordLootLoggerConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DrawManager drawManager;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.keybind())
	{
		@Override
		public void hotkeyPressed()
		{
			sendMessage("", 0, "", "", "manual");
		}
	};

	@Override
	protected void startUp() throws Exception
	{
		keyManager.registerKeyListener(hotkeyListener);
	}

	@Override
	protected void shutDown() throws Exception
	{
		keyManager.unregisterKeyListener(hotkeyListener);
		notificationStarted = false;
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged usernameChanged)
	{
		resetState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState().equals(GameState.LOGIN_SCREEN))
		{
			resetState();
		} else {
			shouldSendMessage = true;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.TRADE
			&& event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		String chatMessage = event.getMessage();

		if (config.includePets() && PET_MESSAGES.stream().anyMatch(chatMessage::contains))
		{
			sendMessage("", 0, "", "", "pet");
		}

		if (config.includeValuableDrops())
		{
			Matcher matcher = VALUABLE_DROP_PATTERN.matcher(chatMessage);
			if (matcher.matches())
			{
				int valuableDropValue = Integer.parseInt(matcher.group(2).replaceAll(",", ""));
				if (valuableDropValue >= config.valuableDropThreshold())
				{
					String[] valuableDrop = matcher.group(1).split(" \\(");
					String valuableDropName = (String) Array.get(valuableDrop, 0);
					String valuableDropValueString = matcher.group(2);
					sendMessage(valuableDropName, 0, "", valuableDropValueString, "valuable drop");
				}
			}
		}

		if (config.includeCollectionLogItems() && chatMessage.startsWith(COLLECTION_LOG_TEXT) && client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION) == 1)
		{
			String entry = Text.removeTags(chatMessage).substring(COLLECTION_LOG_TEXT.length());
			sendMessage(entry, 0, "", "", "collection log");
		}

		if (config.includeRaidLoot())
		{
			if (chatMessage.startsWith("Your completed Chambers of Xeric count is:"))
			{
				Matcher matcher = KC_MESSAGE_PATTERN.matcher(Text.removeTags(chatMessage));
				if (matcher.find())
				{
					raidType = chatMessage.contains("Challenge Mode") ? RaidType.COX_CM : RaidType.COX;
					raidKc = Integer.valueOf(matcher.group());
					return;
				}
			}

			if (chatMessage.startsWith("Your completed Theatre of Blood"))
			{
				Matcher matcher = KC_MESSAGE_PATTERN.matcher(Text.removeTags(chatMessage));
				if (matcher.find())
				{
					raidType = chatMessage.contains("Hard Mode") ? RaidType.TOB_HM : (chatMessage.contains("Story Mode") ? RaidType.TOB_SM : RaidType.TOB);
					raidKc = Integer.valueOf(matcher.group());
					return;
				}
			}

			Matcher uniqueMessage = COX_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
			if (uniqueMessage.matches())
			{
				final String lootRecipient = Text.sanitize(uniqueMessage.group(1)).trim();
				final String dropName = uniqueMessage.group(2).trim();

				if (lootRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
				{
					raidItemName = dropName;
					sendMessage(raidItemName, raidKc, "Theatre of Blood", "", "raid loot");
				}
			}
			if (chatMessage.startsWith(COX_DUST_MESSAGE_TEXT))
			{
				final String dustRecipient = Text.removeTags(chatMessage).substring(COX_DUST_MESSAGE_TEXT.length());
				final String dropName = "Metamorphic dust";

				if (dustRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
				{
					raidItemName = dropName;
				}
			}
			if (chatMessage.startsWith(COX_KIT_MESSAGE_TEXT))
			{
				final String dustRecipient = Text.removeTags(chatMessage).substring(COX_KIT_MESSAGE_TEXT.length());
				final String dropName = "Twisted ancestral colour kit";

				if (dustRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
				{
					raidItemName = dropName;
				}
			}

			Matcher tobUniqueMessage = TOB_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
			if (tobUniqueMessage.matches())
			{
				final String lootRecipient = Text.sanitize(tobUniqueMessage.group(1)).trim();
				final String dropName = tobUniqueMessage.group(2).trim();

				if (lootRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
				{
					raidItemName = dropName;
				}
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int groupId = event.getGroupId();

		if (groupId == CHAMBERS_OF_XERIC_REWARD_GROUP_ID || groupId == THEATRE_OF_BLOOD_REWARD_GROUP_ID)
		{
			if (!config.includeRaidLoot())
			{
				return;
			}

			if (groupId == CHAMBERS_OF_XERIC_REWARD_GROUP_ID && raidItemName != null)
			{
				if (raidType == RaidType.COX)
				{
					sendMessage(raidItemName, raidKc, "Chambers of Xeric", "", "raid loot");
				}
				else if (raidType == RaidType.COX_CM)
				{
					sendMessage(raidItemName, raidKc, "Chambers of Xeric Challenge Mode", "", "raid loot");
				}
				return;
			}
			if (groupId == THEATRE_OF_BLOOD_REWARD_GROUP_ID && raidItemName != null)
			{
				if (raidType != RaidType.TOB && raidType != RaidType.TOB_SM && raidType != RaidType.TOB_HM)
				{
					return;
				}

				switch (raidType)
				{
					case TOB:
						sendMessage(raidItemName, raidKc , "Theatre of Blood", "", "raid loot");
						break;
					case TOB_SM:
						sendMessage(raidItemName, raidKc, "Theatre of Blood Story mode", "", "raid loot");
						break;
					case TOB_HM:
						sendMessage(raidItemName, raidKc, "Theatre of Blood Hard Mode", "", "raid loot");
						break;
					default:
						throw new IllegalStateException();
				}
			}
			raidItemName = null;
			raidType = null;
			raidKc = 0;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired scriptPreFired)
	{
		switch (scriptPreFired.getScriptId())
		{
			case ScriptID.NOTIFICATION_START:
				notificationStarted = true;
				break;
			case ScriptID.NOTIFICATION_DELAY:
				if (!notificationStarted)
				{
					return;
				}
				String notificationTopText = client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT);
				String notificationBottomText = client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT);
				if (notificationTopText.equalsIgnoreCase("Collection log") && config.includeCollectionLogItems())
				{
					String entry = Text.removeTags(notificationBottomText).substring("New item:".length());
					sendMessage(entry, 0, "","", "collection log");
				}
				notificationStarted = false;
				break;
		}
	}

	@Provides
	BetterDiscordLootLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BetterDiscordLootLoggerConfig.class);
	}

	private void sendMessage(String itemName, Integer itemKc, String bossName, String itemValue, String notificationType)
	{
		if (!shouldSendMessage) {return;}

		switch (notificationType)
		{
			case "pet":
				itemName = " a new pet!";
				break;
			case "valuable drop":
				itemName = " a valuable drop: **" + itemName + "**!";
				break;
			case "collection log":
				itemName = " a new collection log item: **" + itemName + "**!";
				break;
			case "raid loot" :
				itemName = " a rare drop from " + bossName + ": **" + itemName + "**!" + (itemKc == null ? "" : itemKc == 0 ? "" : "\nKill Count: **" + itemKc + "**");
				break;
			default:
				itemName = " **a rare drop**";
				break;
		}

		String screenshotString = "**" + client.getLocalPlayer().getName() + "**";
		//TODO: Get value of item for raid drops too
		// - Easy for TOB as it's in the chat
		// - COX might have to use map of item names and ids and grab price from the wiki
		// - If added in could also add option for including regular raid loot above a certain price threshold?
		if (!itemValue.isEmpty())
		{
			screenshotString += " just received" + itemName + "\nApprox Value: **" + itemValue + " coins**";
		}
		else
		{
			screenshotString += " just received" + itemName;
		}


		com.betterdiscordlootlogger.DiscordWebhookBody discordWebhookBody = new com.betterdiscordlootlogger.DiscordWebhookBody();
		discordWebhookBody.setContent(screenshotString);
		sendWebhook(discordWebhookBody);
	}

	private void sendWebhook(com.betterdiscordlootlogger.DiscordWebhookBody discordWebhookBody)
	{
		String configUrl = config.webhook();
		if (Strings.isNullOrEmpty(configUrl))
		{
			return;
		}

		ArrayList<String> urls = new ArrayList<>(Arrays.asList(configUrl.split("\\s*,\\s*")));
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", GSON.toJson(discordWebhookBody));

		for (String url : urls) {
			HttpUrl httpUrl = HttpUrl.parse(url);
			if (httpUrl != null) {
				if (config.sendScreenshot()) {
					sendWebhookWithScreenshot(httpUrl, requestBodyBuilder);
				} else {
					buildRequestAndSend(httpUrl, requestBodyBuilder);
				}
			}
		}
	}

	private void sendWebhookWithScreenshot(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage bufferedImage = (BufferedImage) image;
			byte[] imageBytes;
			try
			{
				imageBytes = convertImageToByteArray(bufferedImage);
			}
			catch (IOException e)
			{
				log.warn("Error converting image to byte array", e);
				return;
			}

			requestBodyBuilder.addFormDataPart("file", "image.png",
				RequestBody.create(MediaType.parse("image/png"), imageBytes));
			buildRequestAndSend(url, requestBodyBuilder);
		});
	}

	private void buildRequestAndSend(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		RequestBody requestBody = requestBodyBuilder.build();
		Request request = new Request.Builder()
			.url(url)
			.post(requestBody)
			.build();
		sendRequest(request);
	}

	private void sendRequest(Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}

	private void resetState()
	{
		shouldSendMessage = false;
	}


	private boolean isInCox()
	{
		return (client.getGameState() == GameState.LOGGED_IN && client.getVarbitValue(Varbits.IN_RAID) == 1);
	}

	private boolean isInTob()
	{
		return (client.getGameState() == GameState.LOGGED_IN &&
			((client.getVarbitValue(Varbits.THEATRE_OF_BLOOD) == 2)));
	}
}
