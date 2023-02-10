# Compose音乐进度条
进度条的thumb高度取决于当前音频信号强度
样本音频为app/src/main/assets/sound.mp3,有需要自己换
不同的音频的信号强度最大值不一样所以，下面这块数值得自己调，不然进度条浮动效果无法保证
```kotlin
Log.e("TAG", "App: currentAverage$currentAverage")
                    mySliderState.avgVolume = if (playerState == PLAYER_STATE_PLAYING) {
                        (currentAverage / 1000f).let { if (it >= 1f) 1f else if (it <= 0f) 0f else it }
                                           ^^^
                    } else {
                        0f
                    }
```



<p align="center">
  <video src="doc/example.mp4" width="100%">
</p>