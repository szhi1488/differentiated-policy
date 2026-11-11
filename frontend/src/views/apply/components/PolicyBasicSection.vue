<script setup>
import { CHANNEL_LABELS, VERIFY_LABELS, SCENE_LABELS } from '../../../constants/policy'
defineProps({ form: { type: Object, required: true }, platforms: { type: Array, required: true } })
defineEmits(['update-field', 'switch-channel', 'toggle-platform', 'select-all'])
</script>
<template>
  <section class="form-section" aria-labelledby="basic-title">
    <h2 id="basic-title">
      <span></span>
      一、基础信息
    </h2>
    <div class="basic-grid" :class="{ 'billion-grid': form.channel === 'BILLION' }">
      <el-form-item label="渠道" required>
        <el-select
          :model-value="form.channel"
          aria-label="渠道"
          @update:model-value="$emit('switch-channel', $event)"
        >
          <el-option
            v-for="(label, code) in CHANNEL_LABELS"
            :key="code"
            :label="label"
            :value="code"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="平台" class="platforms-field" required>
        <div class="platform-options" role="group" aria-label="平台">
          <el-check-tag
            v-for="[code, name] in platforms"
            :key="code"
            :checked="form.platforms.includes(code)"
            @change="$emit('toggle-platform', code)"
          >
            {{ name }}
          </el-check-tag>
          <el-button link type="primary" @click="$emit('select-all')">全部选择</el-button>
        </div>
      </el-form-item>
      <template v-if="form.channel === 'RETAIL'">
        <el-form-item label="核销类型" required>
          <el-radio-group
            :model-value="form.verifyType"
            @update:model-value="$emit('update-field', 'verifyType', $event)"
            aria-label="核销类型"
          >
            <el-radio v-for="(label, code) in VERIFY_LABELS" :key="code" :value="code">
              {{ label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="涉及场景" required>
          <el-radio-group
            :model-value="form.scene"
            @update:model-value="$emit('update-field', 'scene', $event)"
            aria-label="涉及场景"
          >
            <el-radio v-for="(label, code) in SCENE_LABELS" :key="code" :value="code">
              {{ label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="是否为预估政策" required>
          <el-radio-group
            :model-value="form.estimated"
            @update:model-value="$emit('update-field', 'estimated', $event)"
            aria-label="是否为预估政策"
          >
            <el-radio :value="true">是</el-radio>
            <el-radio :value="false">否</el-radio>
          </el-radio-group>
        </el-form-item>
      </template>
    </div>
  </section>
</template>
