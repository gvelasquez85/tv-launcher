#!/bin/bash
# Reverts the changes made to the Sony BRAVIA: re-enables the disabled packages and restores animations.
# Revierte los cambios hechos a la Sony BRAVIA: reactiva los paquetes deshabilitados y las animaciones.
# Usage / Uso: bash revertir_tv.sh <TV_IP>:5555   (network debugging must be on / la depuracion por red debe estar activa)
A=${ADB:-adb}
TV=${1:?Usage: bash revertir_tv.sh <TV_IP>:5555}
$A connect $TV
for p in tv.samba.ssm com.sony.dtv.da.service com.qterics.da.product \
  com.sony.dtv.sonyloglevelsettingsys com.sony.dtv.sonyloglevelsettingvnd \
  com.sony.dtv.sonybugreportsys com.google.android.feedback \
  com.google.android.tv.bugreportsender com.google.android.tungsten.setupwraith \
  com.sony.dtv.sonyselect com.sony.dtv.smartmediaapp com.sony.dtv.osat.music \
  com.sony.dtv.bivlinfo com.android.chrome com.apgsolutionsllc.APGSOLUTIONSLLC0007 \
  com.ionitech.airscreen com.google.android.inputmethod.japanese \
  fr.bouyguestelecom.tv.bboxapi com.mobisystems.fileman com.google.android.katniss; do
  $A -s $TV shell pm enable "$p"
done
# Animations: back al valor por defecto (1x)
$A -s $TV shell 'settings delete global window_animation_scale; settings delete global transition_animation_scale; settings delete global animator_duration_scale'
echo "Listo. Reinicia la TV si algo no aparece."
