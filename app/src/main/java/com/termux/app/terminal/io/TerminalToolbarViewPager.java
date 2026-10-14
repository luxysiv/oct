package com.termux.app.terminal.io;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

public class TerminalToolbarViewPager {

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        String mSavedTextInput;

        public PageAdapter(TermuxActivity activity, String savedTextInput) {
            this.mActivity = activity;
            this.mSavedTextInput = savedTextInput;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            View layout;
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
                ExtraKeysView extraKeysView = (ExtraKeysView) layout;
                extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
                extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
                mActivity.setExtraKeysView(extraKeysView);
                extraKeysView.reload(mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(),
                    mActivity.getTerminalToolbarDefaultHeight());

                // apply extra keys fix if enabled in prefs
                if (mActivity.getProperties().isUsingFullScreen() && mActivity.getProperties().isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity);
                }

            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false);
                final EditText editText = layout.findViewById(R.id.terminal_toolbar_text_input);

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput);
                    mSavedTextInput = null;
                }

                // The field is multi-line so that long prompts can be composed with a
                // full IME (Vietnamese etc.). Only explicit submit actions send; a plain
                // Enter key must fall through so it inserts a newline instead.
                editText.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO) {
                        sendComposedText(mActivity, editText);
                        return true;
                    }
                    return false;
                });

                final View sendButton = layout.findViewById(R.id.terminal_toolbar_text_send);
                if (sendButton != null)
                    sendButton.setOnClickListener(v -> sendComposedText(mActivity, editText));
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
            collection.removeView((View) view);
        }

    }

    /**
     * Send the composed toolbar text to the current terminal session.
     *
     * <p>The text is injected through {@link TerminalEmulator#paste(String)} instead of a raw
     * {@link TerminalSession#write(byte[], int, int)} so that:
     * <ul>
     *   <li>programs that enabled bracketed paste (DECSET 2004), such as the OpenCode TUI,
     *       receive the whole block as a single paste event — one redraw instead of one per
     *       character, which is what made Vietnamese and long input feel laggy;</li>
     *   <li>escape and C1 control characters are stripped and newlines normalised, so a prompt
     *       can no longer be misread as keystrokes (stray submits/cancels);</li>
     *   <li>embedded newlines stay literal, allowing multi-line prompts.</li>
     * </ul>
     *
     * <p>A carriage return is written outside the paste markers to submit the prompt. The text
     * is only cleared once it has been handed off, so a failed send does not lose it.
     */
    static void sendComposedText(TermuxActivity activity, EditText editText) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null) return;

        if (!session.isRunning()) {
            activity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            return;
        }

        String text = editText.getText().toString();
        if (text.isEmpty()) {
            // Nothing typed: treat it as pressing Enter in the terminal.
            session.write("\r");
            return;
        }

        TerminalView terminalView = activity.getTerminalView();
        TerminalEmulator emulator = terminalView != null ? terminalView.mEmulator : null;
        if (emulator != null) {
            emulator.paste(text);
            session.write("\r");
        } else {
            // Fallback for when the emulator is not available yet.
            session.write(text);
        }
        editText.setText("");
        // Keep the compose field focused so the IME stays up for the next prompt.
        editText.requestFocus();
    }



    public static class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {

        final TermuxActivity mActivity;
        final ViewPager mTerminalToolbarViewPager;

        public OnPageChangeListener(TermuxActivity activity, ViewPager viewPager) {
            this.mActivity = activity;
            this.mTerminalToolbarViewPager = viewPager;
        }

        @Override
        public void onPageSelected(int position) {
            if (position == 0) {
                mActivity.getTerminalView().requestFocus();
            } else {
                final EditText editText = mTerminalToolbarViewPager.findViewById(R.id.terminal_toolbar_text_input);
                if (editText != null) editText.requestFocus();
            }
        }

    }

}
