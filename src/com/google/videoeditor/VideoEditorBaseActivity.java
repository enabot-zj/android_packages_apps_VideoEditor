/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.google.videoeditor;

import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.videoeditor.AudioTrack;
import android.media.videoeditor.MediaItem;
import android.media.videoeditor.MediaVideoItem;
import android.media.videoeditor.Transition;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.videoeditor.service.ApiService;
import com.google.videoeditor.service.MovieAudioTrack;
import com.google.videoeditor.service.MovieEffect;
import com.google.videoeditor.service.MovieMediaItem;
import com.google.videoeditor.service.MovieOverlay;
import com.google.videoeditor.service.MovieTransition;
import com.google.videoeditor.service.VideoEditorProject;
import com.google.videoeditor.service.ApiService.ApiServiceListener;
import com.google.videoeditor.widgets.AudioTrackLinearLayout;
import com.google.videoeditor.widgets.MediaLinearLayout;
import com.google.videoeditor.widgets.OverlayLinearLayout;

/**
 * This activity handles callbacks from the service and manages the project
 */
public abstract class VideoEditorBaseActivity extends Activity {
    // Logging
    private static final String TAG = "VideoEditorBase";

    protected static final int DIALOG_DELETE_BAD_PROJECT_ID = 100;

    // State keys
    private static final String STATE_PROJECT_PATH = "path";
    private static final String STATE_EXPORT_FILENAME = "export_filename";

    // Dialog parameters
    protected static final String PARAM_PROJECT_PATH = "path";

    // Instance variables
    private final ServiceListener mServiceListener = new ServiceListener();
    protected String mProjectPath;
    protected VideoEditorProject mProject;
    protected String mPendingExportFilename;
    private boolean mProjectEditState;

    /**
     * The service listener
     */
    private class ServiceListener extends ApiServiceListener {
        /*
         * {@inheritDoc}
         */
        @Override
        public void onProjectEditState(String projectPath, boolean projectEdited) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProjectEditState != projectEdited) {
                mProjectEditState = projectEdited;
                onProjectEditStateChange(projectEdited);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorCreated(String projectPath, VideoEditorProject project,
                List<MediaItem> mediaItems, List<AudioTrack> audioTracks, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            // Check if an error occurred
            if (exception != null) {
                // Invalidate the project path
                mProjectPath = null;

                enterDisabledState(R.string.editor_no_project);
                // Display an error
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_create_error,
                        Toast.LENGTH_LONG).show();
            } else {
                enterReadyState();

                mProject = project;
                initializeFromProject(true);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorLoaded(String projectPath, VideoEditorProject project,
                List<MediaItem> mediaItems, List<AudioTrack> audioTracks, Exception exception) {
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            // Check if an error occurred
            if (exception != null) {
                mProjectPath = null;

                enterDisabledState(R.string.editor_no_project);

                final Bundle bundle = new Bundle();
                bundle.putString(VideoEditorActivity.PARAM_PROJECT_PATH, projectPath);
                showDialog(DIALOG_DELETE_BAD_PROJECT_ID, bundle);
            } else {
                // The project may be loaded already. This can happen when we
                // create a new project
                if (mProject == null) {
                    mProject = project;
                    initializeFromProject(true);
                }
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorAspectRatioSet(String projectPath, int aspectRatio,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_aspect_ratio_error,
                        Toast.LENGTH_LONG).show();
            } else {
                // The aspect ratio has changed
                setAspectRatio(aspectRatio);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorThemeApplied(String projectPath, String theme,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_apply_theme_error,
                        Toast.LENGTH_LONG).show();
            } else {
                getMediaLayout().addMediaItems(mProject.getMediaItems());
                getOverlayLayout().addMediaItems(mProject.getMediaItems());
                getAudioTrackLayout().addAudioTracks(mProject.getAudioTracks());

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorGeneratePreviewProgress(String projectPath, String className,
                String itemId, int action, int progress) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "onVideoEditorGeneratePreviewProgress: " + className + " " + progress);
            }

            if (className == null) { // Last callback (after all items are generated)
                if (action == ApiService.ACTION_UPDATE_FRAME) {
                    showPreviewFrame();
                }
            } else if (MediaItem.class.getCanonicalName().equals(className)) {
                getMediaLayout().onGeneratePreviewMediaItemProgress(itemId, action, progress);
            } else if (Transition.class.getCanonicalName().equals(className)) {
                getMediaLayout().onGeneratePreviewTransitionProgress(itemId, action, progress);
            } else if (AudioTrack.class.getCanonicalName().equals(className)) {
                getAudioTrackLayout().onGeneratePreviewProgress(itemId, action, progress);
            } else {
                Log.w(TAG, "Unsupported storyboard item type: " + className);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorExportProgress(String projectPath, String filename,
                int progress) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (!filename.equals(mPendingExportFilename)) {
                return;
            }

            // Update the export progress
            onExportProgress(progress);
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorExportComplete(String projectPath, String filename,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (!filename.equals(mPendingExportFilename)) {
                return;
            }

            onExportComplete();

            mPendingExportFilename = null;

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_export_error,
                        Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorSaved(String projectPath, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_saved_error,
                        Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorReleased(String projectPath, Exception exception) {
            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_release_error,
                        Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onVideoEditorDeleted(String projectPath, Exception exception) {
            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_delete_error,
                        Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemAdded(String projectPath, String mediaItemId,
                MovieMediaItem mediaItem, String afterMediaItemId, Class<?> mediaItemClass,
                Integer newAspectRatio, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                if (mediaItemClass.getCanonicalName().equals(MediaVideoItem.class)) {
                    Toast.makeText(VideoEditorBaseActivity.this,
                            R.string.editor_add_video_clip_error, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_add_image_error,
                            Toast.LENGTH_LONG).show();
                }
            } else {
                getMediaLayout().insertMediaItem(mediaItem, afterMediaItemId);
                getOverlayLayout().insertMediaItem(mediaItem, afterMediaItemId);

                if (newAspectRatio != null) {
                    // The aspect ratio has changed
                    setAspectRatio(newAspectRatio);
                }

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemMoved(String projectPath, String mediaItemId,
                String afterMediaItemId, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_move_media_item_error,
                            Toast.LENGTH_LONG).show();
            } else {
                // Update the entire timeline
                getMediaLayout().addMediaItems(mProject.getMediaItems());
                getOverlayLayout().addMediaItems(mProject.getMediaItems());

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemRemoved(String projectPath, String mediaItemId,
                MovieTransition transition, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_remove_media_item_error, Toast.LENGTH_LONG).show();
            } else {
                // Remove the media item and bounding transitions
                getMediaLayout().removeMediaItem(mediaItemId, transition);
                getOverlayLayout().removeMediaItem(mediaItemId);

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemRenderingModeSet(String projectPath, String mediaItemId,
                int renderingMode, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_rendering_mode_error, Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemDurationSet(String projectPath, String mediaItemId,
                long durationMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_media_item_duration_error, Toast.LENGTH_LONG).show();
            } else {
                final MovieMediaItem mediaItem = mProject.getMediaItem(mediaItemId);
                // Update the media item
                getMediaLayout().updateMediaItem(mediaItem);
                getOverlayLayout().updateMediaItem(mediaItem);

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onMediaItemBoundariesSet(String projectPath, String mediaItemId,
                long beginBoundaryMs, long endBoundaryMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_media_item_boundaries_error, Toast.LENGTH_LONG).show();
            } else {
                final MovieMediaItem mediaItem = mProject.getMediaItem(mediaItemId);
                getMediaLayout().updateMediaItem(mediaItem);
                getOverlayLayout().updateMediaItem(mediaItem);

                // Place the cursor at the beginning of the trimmed media item
                //movePlayhead(mProject.getMediaItemBeginTime(mediaItemId));
                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public boolean onMediaItemThumbnails(String projectPath, String mediaItemId,
                Bitmap[] thumbnails, long startMs, long endMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return false;
            }

            if (mProject == null) {
                return false;
            }

            if (exception != null) {
                return false;
            } else {
                return getMediaLayout().setMediaItemThumbnails(mediaItemId, thumbnails, startMs,
                        endMs);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onTransitionInserted(String projectPath, MovieTransition transition,
                String afterMediaId, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                 Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_add_transition_error,
                            Toast.LENGTH_LONG).show();
            } else {
                getMediaLayout().addTransition(transition, afterMediaId);

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onTransitionRemoved(String projectPath, String transitionId,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_remove_transition_error, Toast.LENGTH_LONG).show();
            } else {
                getMediaLayout().removeTransition(transitionId);

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onTransitionDurationSet(String projectPath, String transitionId,
                long durationMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_transition_duration_error, Toast.LENGTH_LONG).show();
            } else {
                getMediaLayout().updateTransition(transitionId);
                getOverlayLayout().refresh();

                updateTimelineDuration();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public boolean onTransitionThumbnails(String projectPath, String transitionId,
                Bitmap[] thumbnails, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return false;
            }

            if (mProject == null) {
                return false;
            }

            if (exception != null) {
                return false;
            } else {
                return getMediaLayout().setTransitionThumbnails(transitionId, thumbnails);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onOverlayAdded(String projectPath, MovieOverlay overlay,
                String mediaItemId, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_add_overlay_error,
                            Toast.LENGTH_LONG).show();
            } else {
                getOverlayLayout().addOverlay(mediaItemId, overlay);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onOverlayRemoved(String projectPath, String overlayId,
                String mediaItemId, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_remove_overlay_error,
                            Toast.LENGTH_LONG).show();
            } else {
                getOverlayLayout().removeOverlay(mediaItemId, overlayId);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onOverlayStartTimeSet(String projectPath, String overlayId,
                String mediaItemId, long startTimeMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_start_time_overlay_error, Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onOverlayDurationSet(String projectPath, String overlayId,
                String mediaItemId, long durationMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_duration_overlay_error, Toast.LENGTH_LONG).show();
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onOverlayUserAttributesSet(String projectPath, String overlayId,
                String mediaItemId, Bundle userAttributes, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_user_attributes_overlay_error,
                        Toast.LENGTH_LONG).show();
            } else {
                getOverlayLayout().updateOverlayAttributes(mediaItemId, overlayId, userAttributes);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onEffectAdded(String projectPath, MovieEffect effect, String mediaItemId,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_add_effect_error,
                            Toast.LENGTH_LONG).show();
            } else {
                getMediaLayout().updateMediaItem(mProject.getMediaItem(mediaItemId));
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onEffectRemoved(String projectPath, String effectId, String mediaItemId,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_remove_effect_error,
                            Toast.LENGTH_LONG).show();
            } else {
                // Remove the effect
                getMediaLayout().updateMediaItem(mProject.getMediaItem(mediaItemId));
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onAudioTrackAdded(String projectPath, MovieAudioTrack audioTrack,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this, R.string.editor_add_audio_track_error,
                            Toast.LENGTH_LONG).show();
            } else {
                getAudioTrackLayout().addAudioTrack(audioTrack);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onAudioTrackRemoved(String projectPath, String audioTrackId,
                Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_remove_audio_track_error, Toast.LENGTH_LONG).show();
            } else {
                getAudioTrackLayout().removeAudioTrack(audioTrackId);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onAudioTrackBoundariesSet(String projectPath, String audioTrackId,
                long beginBoundaryMs, long endBoundaryMs, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception != null) {
                Toast.makeText(VideoEditorBaseActivity.this,
                        R.string.editor_set_audio_track_boundaries_error,
                        Toast.LENGTH_LONG).show();
            } else {
                getAudioTrackLayout().updateAudioTrack(audioTrackId);
            }
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onAudioTrackExtractAudioWaveformProgress(String projectPath,
                String audioTrackId, int progress) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            getAudioTrackLayout().setWaveformExtractionProgress(audioTrackId, progress);
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void onAudioTrackExtractAudioWaveformComplete(String projectPath,
                String audioTrackId, Exception exception) {
            // Check if the VideoEditor is the one we are expecting
            if (!projectPath.equals(mProjectPath)) {
                return;
            }

            if (mProject == null) {
                return;
            }

            if (exception == null) {
                getAudioTrackLayout().setWaveformExtractionComplete(audioTrackId);
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.video_editor);

        if (savedInstanceState != null) {
            mProjectPath = savedInstanceState.getString(STATE_PROJECT_PATH);
            mPendingExportFilename = savedInstanceState.getString(STATE_EXPORT_FILENAME);
        } else {
            final Intent intent = getIntent();
            mProjectPath = intent.getStringExtra(ProjectsActivity.PARAM_OPEN_PROJECT_PATH);
            if (Intent.ACTION_INSERT.equals(intent.getAction())) {
                ApiService.createVideoEditor(this, mProjectPath,
                        intent.getStringExtra(ProjectsActivity.PARAM_CREATE_PROJECT_NAME),
                        new String[0], new String[0], null);
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void onResume() {
        super.onResume();

        mProjectEditState = ApiService.isProjectEdited(mProjectPath);
        ApiService.registerListener(mServiceListener);

        // Check if we need to load the project
        if (mProjectPath != null) {
            if (mProject == null) {
                // We need to load the project
                ApiService.loadVideoEditor(this, mProjectPath);
                enterTransitionalState(R.string.editor_loading_project);
            } else { // The project is already loaded
                initializeFromProject(false);
            }
        } else {
            enterDisabledState(R.string.editor_no_project);
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void onPause() {
        super.onPause();
        ApiService.unregisterListener(mServiceListener);

        if (mProject != null) {
            // Save the contents of the current project
            ApiService.saveVideoEditor(this, mProjectPath);
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        // If we have an active project release the VideoEditor
        if (mProjectPath != null) {
            if (!isChangingConfigurations()) {
                ApiService.releaseVideoEditor(this, mProjectPath);
            }

            mProjectPath = null;
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(STATE_PROJECT_PATH, mProjectPath);
        outState.putString(STATE_EXPORT_FILENAME, mPendingExportFilename);
    }

    /**
     * @return true if the project is edited
     */
    protected boolean isProjectEdited() {
        return mProjectEditState;
    }

    /**
     * Enter the disabled state
     *
     * @param statusStringId The status string id
     */
    protected abstract void enterDisabledState(int statusStringId);

    /**
     * Enter the transitional state
     *
     * @param statusStringId The status string id
     */
    protected abstract void enterTransitionalState(int statusStringId);

    /**
     * Enter the ready state
     */
    protected abstract void enterReadyState();

    /**
     * Show the preview frame at the current playhead position
     *
     * @return true if the surface is created, false otherwise.
     */
    protected abstract boolean showPreviewFrame();

    /**
     * The duration of the timeline has changed
     */
    protected abstract void updateTimelineDuration();

    /**
     * Move the playhead to the specified position
     *
     * @param timeMs The time position
     */
    protected abstract void movePlayhead(long timeMs);

    /**
     * Change the aspect ratio
     *
     * @param aspectRatio The new aspect ratio
     */
    protected abstract void setAspectRatio(int aspectRatio);

    /**
     * Initialize the project when restored from storage.
     *
     * @param updateUI true to update the UI
     *
     * Note that this method may be called also after the project was loaded
     */
    protected abstract void initializeFromProject(boolean updateUI);

    /**
     * @return The media layout
     */
    protected abstract MediaLinearLayout getMediaLayout();

    /**
     * @return The overlay layout
     */
    protected abstract OverlayLinearLayout getOverlayLayout();

    /**
     * @return The audio layout
     */
    protected abstract AudioTrackLinearLayout getAudioTrackLayout();

    /**
     * The export is progressing
     */
    protected abstract void onExportProgress(int progress);

    /**
     * The export has completed
     */
    protected abstract void onExportComplete();

    /**
     * @param projectEdited true if the project is edited
     */
    protected abstract void onProjectEditStateChange(boolean projectEdited);
}
