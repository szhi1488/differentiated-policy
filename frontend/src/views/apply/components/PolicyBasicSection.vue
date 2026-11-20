<script setup>
import {
  PLATFORM_LABELS,
  CHANNEL_LABELS,
  VERIFY_LABELS,
  SCENE_LABELS,
} from '../../../constants/policy'
defineProps({
  form: { type: Object, required: true },
  platforms: { type: Array, required: true },
  assigned: Array,
  platformError: Error,
  removedPlatforms: { type: Array, required: true },
  readOnly: Boolean,
})
defineEmits(['update-field', 'switch-channel', 'toggle-platform', 'select-all', 'retry-platforms'])
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
        <span v-if="readOnly">
          {{ form.platforms.map((code) => PLATFORM_LABELS[code]).join('、') || '未填写' }}
        </span>
        <div v-else class="platform-options" role="group" aria-label="平台">
          <el-check-tag
            v-for="[code, name] in platforms"
            :key="code"
            :checked="form.platforms.includes(code)"
            @change="$emit('toggle-platform', code)"
          >
            {{ name }}
          </el-check-tag>
          <el-button v-if="platforms.length" link type="primary" @click="$emit('select-all')">
            全部选择
          </el-button>
          <template v-if="platformError">
            <span>{{ platformError.message }}</span>
            <el-button link type="primary" @click="$emit('retry-platforms')">
              重新读取负责平台
            </el-button>
          </template>
          <span v-else-if="!assigned">正在读取负责平台…</span>
          <span v-else-if="!assigned.length">尚未分配负责平台，请联系管理员。</span>
          <span v-else-if="!platforms.length">本渠道没有分配给你的平台，请切换渠道。</span>
          <el-button
            v-for="code in removedPlatforms"
            :key="code"
            type="warning"
            plain
            size="small"
            @click="$emit('toggle-platform', code)"
          >
            移除已移交的{{ PLATFORM_LABELS[code] }}
          </el-button>
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
