/*
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.navigationbar.gestural.pie;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PieItemParserTest {

    @Test
    public void parseValidMix() {
        List<PieItem> items = PieItemRepository.parseRaw(
                "app:com.spotify.music:Spotify;action:7:Screenshot;activity:com.foo/.Bar:Bar", null);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).type).isEqualTo(PieItem.TYPE_APP);
        assertThat(items.get(0).packageName).isEqualTo("com.spotify.music");
        assertThat(items.get(1).type).isEqualTo(PieItem.TYPE_ACTION);
        assertThat(items.get(1).actionId).isEqualTo(7);
        assertThat(items.get(2).type).isEqualTo(PieItem.TYPE_ACTIVITY);
        assertThat(items.get(2).className).isEqualTo(".Bar");
    }

    @Test
    public void parseEmpty() {
        assertThat(PieItemRepository.parseRaw("", null)).isEmpty();
        assertThat(PieItemRepository.parseRaw(null, null)).isEmpty();
    }

    @Test
    public void parseMalformedDropped() {
        List<PieItem> items = PieItemRepository.parseRaw(
                "bogus;app:;action:xx:X;activity:noclash:Y;app:com.ok:Ok", null);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).packageName).isEqualTo("com.ok");
    }

    @Test
    public void parseTruncatesToFive() {
        List<PieItem> items = PieItemRepository.parseRaw(
                "action:1:A;action:2:B;action:3:C;action:4:D;action:5:E;action:6:F", null);
        assertThat(items).hasSize(5);
    }

    @Test
    public void parseLabelWithColon() {
        List<PieItem> items = PieItemRepository.parseRaw("action:7:Take: shot", null);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).label).isEqualTo("Take: shot");
    }

    @Test
    public void parseDropsUninstalled() throws Exception {
        PackageManager pm = Mockito.mock(PackageManager.class);
        Mockito.when(pm.getPackageInfo("com.gone", 0)).thenThrow(
                new PackageManager.NameNotFoundException());
        Mockito.when(pm.getPackageInfo("com.kept", 0)).thenReturn(null);
        List<PieItem> items = PieItemRepository.parseRaw(
                "app:com.gone:Gone;app:com.kept:Kept", pm);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).packageName).isEqualTo("com.kept");
    }

    @Test
    public void filterOutPackageKeepsOrder() {
        String raw = "app:com.a:A;action:7:S;app:com.b:B";
        String filtered = PieItemRepository.filterOutPackage(raw, "com.a");
        assertThat(filtered).isEqualTo("action:7:S;app:com.b:B");
        String rawActivity = "activity:com.a/.X:X;app:com.a:A;app:com.c:C";
        assertThat(PieItemRepository.filterOutPackage(rawActivity, "com.a"))
                .isEqualTo("app:com.c:C");
    }
}
