package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PlayerSetting;

public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] all = activity.getResources().getStringArray(R.array.select_player_kernel);
        CharSequence[] items = visibleItems(all);
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, items, visibleIndex(current), which -> notifySelected(current, which, listener));
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        String[] all = fragment.getResources().getStringArray(R.array.select_player_kernel);
        CharSequence[] items = visibleItems(all);
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, items, visibleIndex(current), which -> notifySelected(current, which, listener));
    }

    /** Only EXO and VLC are exposed; their slot indices in the resource array are reused. */
    private static CharSequence[] visibleItems(String[] all) {
        int[] players = PlayerSetting.visiblePlayers();
        CharSequence[] items = new CharSequence[players.length];
        for (int i = 0; i < players.length; i++) items[i] = all[players[i]];
        return items;
    }

    private static int visibleIndex(int player) {
        return player == PlayerSetting.VLC ? 1 : 0;
    }

    /** which is an index into visiblePlayers(), map it back to the real kernel constant. */
    private static void notifySelected(int current, int which, Listener listener) {
        int[] players = PlayerSetting.visiblePlayers();
        int target = which >= 0 && which < players.length ? PlayerSetting.sanitizePlayer(players[which]) : current;
        if (target != current && listener != null) listener.onSelected(target);
    }
}
